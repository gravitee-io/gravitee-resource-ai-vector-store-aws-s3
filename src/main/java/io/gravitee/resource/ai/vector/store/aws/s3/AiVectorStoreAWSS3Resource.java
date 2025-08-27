/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.resource.ai.vector.store.aws.s3;

import io.gravitee.resource.ai.vector.store.api.*;
import io.gravitee.resource.ai.vector.store.aws.s3.configuration.AWSS3VectorsConfiguration;
import io.gravitee.resource.ai.vector.store.aws.s3.configuration.AiVectorStoreAWSS3Configuration;
import io.gravitee.resource.ai.vector.store.aws.s3.configuration.EncryptionType;
import io.gravitee.resource.ai.vector.store.aws.s3.wrapper.FloatArrayList;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient;
import software.amazon.awssdk.services.s3vectors.model.*;

/**
 * @author Derek Thompson (derek.thompson at graviteesource.com)
 * @author GraviteeSource Team
 */
@Slf4j
public class AiVectorStoreAWSS3Resource extends AiVectorStoreResource<AiVectorStoreAWSS3Configuration> {

  AWSS3VectorsConfiguration awsS3VectorsConfiguration;
  AiVectorStoreProperties properties;
  S3VectorsAsyncClient s3VectorsClient;

  final AtomicBoolean activated = new AtomicBoolean(false);

  private static final String RETRIEVAL_CONTEXT_KEY_ATTR = "retrieval_context_key";
  private static final String EXPIRE_AT_ATTR = "expireAt";
  private static final String TEXT_ATTR = "text";
  private static final String VECTOR_ATTR = "vector";

  private static final String FIND_RELEVANT_OPERATION = "findRelevant";
  private static final String ADD_OPERATION = "add";
  private static final String REMOVE_OPERATION = "remove";
  private static final String INITIALIZATION_OPERATION = "initialization";

  @Override
  public void doStart() throws Exception {
    super.doStart();

    properties = super.configuration().properties();
    awsS3VectorsConfiguration = super.configuration().awsS3VectorsConfiguration();

    var builder = S3VectorsAsyncClient.builder().region(Region.of(awsS3VectorsConfiguration.region()));
    builder.credentialsProvider(
      buildAwsCredentialsProvider(
        awsS3VectorsConfiguration.awsAccessKeyId(),
        awsS3VectorsConfiguration.awsSecretAccessKey(),
        awsS3VectorsConfiguration.sessionToken()
      )
    );
    s3VectorsClient = builder.build();

    ensureBucketAndIndex()
      .subscribeOn(Schedulers.io())
      .doOnComplete(() -> {
        log.debug("AWS S3 bucket and Vectors index ready.");
        activated.set(true);
        if (properties.readOnly()) {
          logReadOnly(INITIALIZATION_OPERATION);
        }
      })
      .onErrorResumeNext(error -> {
        log.error("Error ensuring AWS S3 bucket/index", error);
        activated.set(false);
        return Completable.complete();
      })
      .subscribe();
  }

  @Override
  public void doStop() throws Exception {
    s3VectorsClient.close();
    super.doStop();
  }

  @Override
  public Completable add(VectorEntity vectorEntity) {
    if (!activated.get()) {
      logNotActivated(ADD_OPERATION);
      return Completable.complete();
    }
    if (properties.readOnly()) {
      logReadOnly(ADD_OPERATION);
      return Completable.complete();
    }
    if (vectorEntity.id() == null || vectorEntity.id().isBlank()) {
      return Completable.error(new IllegalArgumentException("VectorEntity.id must not be null or blank"));
    }

    return Completable
      .defer(() -> {
        List<Float> vectorList = FloatArrayList.of(vectorEntity.vector());
        VectorData vectorData = VectorData.fromFloat32(vectorList);
        Map<String, Document> metadata = new java.util.HashMap<>();
        if (properties.allowEviction()) {
          long evictTime = properties.evictTime();
          TimeUnit evictTimeUnit = properties.evictTimeUnit();
          Instant expireAt = Instant.now().plus(evictTime, evictTimeUnit.toChronoUnit());
          metadata.put(EXPIRE_AT_ATTR, Document.fromString(expireAt.toString()));
        }
        if (vectorEntity.metadata() != null && vectorEntity.metadata().containsKey(RETRIEVAL_CONTEXT_KEY_ATTR)) {
          Object contextValue = vectorEntity.metadata().get(RETRIEVAL_CONTEXT_KEY_ATTR);
          if (contextValue != null) {
            metadata.put(RETRIEVAL_CONTEXT_KEY_ATTR, Document.fromString(contextValue.toString()));
          }
        }
        PutInputVector putVector = PutInputVector
          .builder()
          .key(vectorEntity.id())
          .data(vectorData)
          .metadata(Document.fromMap(metadata))
          .build();
        PutVectorsRequest putRequest = PutVectorsRequest
          .builder()
          .vectorBucketName(awsS3VectorsConfiguration.vectorBucketName())
          .indexName(awsS3VectorsConfiguration.vectorIndexName())
          .vectors(putVector)
          .build();
        var fut = s3VectorsClient.putVectors(putRequest);
        return Completable.fromCompletionStage(fut).doOnDispose(() -> fut.cancel(true));
      })
      .doOnComplete(() -> log.debug("Vector {} put to AWS S3 Vectors.", vectorEntity.id()));
  }

  @Override
  public Flowable<VectorResult> findRelevant(VectorEntity queryEntity) {
    if (!activated.get()) {
      logNotActivated(FIND_RELEVANT_OPERATION);
      return Flowable.empty();
    }
    return Flowable
      .defer(() -> {
        List<Float> vectorList = FloatArrayList.of(queryEntity.vector());
        QueryVectorsRequest.Builder reqBuilder = QueryVectorsRequest
          .builder()
          .vectorBucketName(awsS3VectorsConfiguration.vectorBucketName())
          .indexName(awsS3VectorsConfiguration.vectorIndexName())
          .queryVector(VectorData.fromFloat32(vectorList))
          .topK(properties.maxResults())
          .returnDistance(true)
          .returnMetadata(true);

        // Add metadata filter if retrieval_context_key exists
        if (queryEntity.metadata() != null && queryEntity.metadata().containsKey(RETRIEVAL_CONTEXT_KEY_ATTR)) {
          Object contextValue = queryEntity.metadata().get(RETRIEVAL_CONTEXT_KEY_ATTR);
          if (contextValue != null) {
            // S3 Vectors expects filter as a Document
            Map<String, Document> filterMap = Map.of(
              RETRIEVAL_CONTEXT_KEY_ATTR,
              Document.fromString(contextValue.toString())
            );
            reqBuilder.filter(Document.fromMap(filterMap));
          }
        }
        QueryVectorsRequest req = reqBuilder.build();
        var fut = s3VectorsClient.queryVectors(req);
        return Single
          .fromCompletionStage(fut)
          .doOnDispose(() -> fut.cancel(true))
          .flattenAsFlowable(QueryVectorsResponse::vectors)
          .map(result -> {
            Map<String, Object> metadata = new java.util.HashMap<>();
            var resultMetadata = result.metadata();
            if (resultMetadata != null && resultMetadata.isMap()) {
              metadata =
                resultMetadata
                  .asMap()
                  .entrySet()
                  .stream()
                  .map(e -> Map.entry(e.getKey(), extractValue(e.getValue())))
                  .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            }
            String text = (String) metadata.remove(TEXT_ATTR);
            metadata.remove(VECTOR_ATTR);
            float score = properties.similarity().normalizeDistance(result.distance());
            return new VectorResult(new VectorEntity(result.key(), text, metadata), score);
          })
          .filter(vr -> vr.score() >= properties.threshold());
      })
      .sorted((a, b) -> Float.compare(b.score(), a.score()));
  }

  @Override
  public void remove(VectorEntity vectorEntity) {
    if (!activated.get()) {
      logNotActivated(REMOVE_OPERATION);
      return;
    }
    if (properties.readOnly()) {
      logReadOnly(REMOVE_OPERATION);
      return;
    }
    DeleteVectorsRequest.Builder deleteBuilder = DeleteVectorsRequest
      .builder()
      .vectorBucketName(awsS3VectorsConfiguration.vectorBucketName())
      .indexName(awsS3VectorsConfiguration.vectorIndexName())
      .keys(vectorEntity.id());

    DeleteVectorsRequest deleteRequest = deleteBuilder.build();

    var fut = s3VectorsClient.deleteVectors(deleteRequest);
    fut.whenComplete((resp, err) -> {
      if (err != null) {
        log.error("Error removing vector {} from AWS S3 Vectors", vectorEntity.id(), err);
      } else {
        log.debug("Vector {} removed from AWS S3 Vectors.", vectorEntity.id());
      }
    });
  }

  // --- Package private helper methods below ---

  AwsCredentialsProvider buildAwsCredentialsProvider(String accessKeyId, String secretAccessKey, String sessionToken) {
    if (sessionToken != null && !sessionToken.isEmpty()) {
      return StaticCredentialsProvider.create(AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken));
    } else {
      return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
    }
  }

  Single<Boolean> indexExists() {
    return Single.defer(() -> {
      var fut = s3VectorsClient.getIndex(b ->
        b
          .vectorBucketName(awsS3VectorsConfiguration.vectorBucketName())
          .indexName(awsS3VectorsConfiguration.vectorIndexName())
      );

      return Single
        .fromCompletionStage(fut)
        .map(resp -> true)
        .onErrorResumeNext(err -> {
          Throwable e = unwrapCompletion(err);
          if (e instanceof S3VectorsException s3ve && s3ve.statusCode() == 404) {
            log.warn("Index does not exist: {}", awsS3VectorsConfiguration.vectorIndexName());
            return Single.just(false);
          }
          log.error("Error checking index existence: {}", e.getMessage());
          return Single.error(e);
        })
        .doOnDispose(() -> fut.cancel(true));
    });
  }

  Single<Boolean> bucketExists(String bucketName) {
    return Single.defer(() -> {
      var fut = s3VectorsClient.getVectorBucket(b -> b.vectorBucketName(bucketName));
      return Single
        .fromCompletionStage(fut)
        .map(resp -> true)
        .onErrorResumeNext(err -> {
          Throwable e = unwrapCompletion(err);
          if (e instanceof S3VectorsException s3ve && s3ve.statusCode() == 404) {
            log.warn("Bucket does not exist: {}", bucketName);
            return Single.just(false);
          }
          log.error("Error checking bucket existence: {}", e.getMessage());
          return Single.error(e);
        })
        .doOnDispose(() -> fut.cancel(true));
    });
  }

  Completable ensureBucketAndIndex() {
    return bucketExists(awsS3VectorsConfiguration.vectorBucketName())
      .flatMapCompletable(exists -> exists ? Completable.complete() : createBucket())
      .andThen(indexExists().flatMapCompletable(exists -> exists ? Completable.complete() : createIndex()));
  }

  Completable createBucket() {
    return Completable.defer(() -> {
      CreateVectorBucketRequest.Builder builder = CreateVectorBucketRequest
        .builder()
        .vectorBucketName(awsS3VectorsConfiguration.vectorBucketName());

      if (awsS3VectorsConfiguration.encryption() != null && awsS3VectorsConfiguration.encryption() != EncryptionType.NONE) {
        EncryptionConfiguration.Builder encBuilder = EncryptionConfiguration.builder();
        switch (awsS3VectorsConfiguration.encryption()) {
          case SSE_KMS -> {
            encBuilder.sseType(SseType.AWS_KMS);
            if (awsS3VectorsConfiguration.kmsKeyId() != null && !awsS3VectorsConfiguration.kmsKeyId().isEmpty()) {
              encBuilder.kmsKeyArn(awsS3VectorsConfiguration.kmsKeyId());
            } else {
              return Completable.error(
                new IllegalArgumentException("SSE_KMS encryption selected but no KMS Key ARN provided.")
              );
            }
          }
          case SSE_S3 -> encBuilder.sseType(SseType.AES256);
          default -> encBuilder.sseType(SseType.UNKNOWN_TO_SDK_VERSION);
        }
        builder.encryptionConfiguration(encBuilder.build());
      }

      var createFut = s3VectorsClient.createVectorBucket(builder.build());
      return Completable
        .fromCompletionStage(createFut)
        .doOnDispose(() -> createFut.cancel(true))
        .doOnComplete(() -> log.info("Created S3 Vectors bucket: {}", awsS3VectorsConfiguration.vectorBucketName()))
        .doOnError(err ->
          log.error("Failed to create bucket {}: {}", awsS3VectorsConfiguration.vectorBucketName(), err.getMessage(), err)
        );
    });
  }

  Completable createIndex() {
    return Completable.defer(() -> {
      CreateIndexRequest req = CreateIndexRequest
        .builder()
        .vectorBucketName(awsS3VectorsConfiguration.vectorBucketName())
        .indexName(awsS3VectorsConfiguration.vectorIndexName())
        .dimension(properties.embeddingSize())
        .distanceMetric(properties.similarity().name().toLowerCase())
        .dataType(DataType.FLOAT32)
        .build();

      var fut = s3VectorsClient.createIndex(req);
      return Completable
        .fromCompletionStage(fut)
        .doOnDispose(() -> fut.cancel(true))
        .doOnComplete(() -> log.debug("Index created: {}", awsS3VectorsConfiguration.vectorIndexName()))
        .doOnError(err -> log.warn("Index may already exist or could not be created: {}", err.getMessage(), err));
    });
  }

  static Object extractValue(Document v) {
    if (v.isBoolean()) {
      return v.asBoolean();
    } else if (v.isNumber()) {
      return v.asNumber();
    } else if (v.isString()) {
      return v.asString();
    } else if (v.isMap()) {
      return v.asMap().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> extractValue(e.getValue())));
    } else if (v.isList()) {
      return v.asList();
    } else {
      return v.toString();
    }
  }

  void logReadOnly(String operation) {
    log.warn(
      "Resource is read-only. Skipping {} operation for bucket {} and index {}.",
      operation,
      awsS3VectorsConfiguration.vectorBucketName(),
      awsS3VectorsConfiguration.vectorIndexName()
    );
  }

  void logNotActivated(String operation) {
    log.warn(
      "Resource not activated. Skipping {} operation for bucket {} and index {}.",
      operation,
      awsS3VectorsConfiguration.vectorBucketName(),
      awsS3VectorsConfiguration.vectorIndexName()
    );
  }

  static Throwable unwrapCompletion(Throwable t) {
    if (t instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null) return ce.getCause();
    if (t instanceof java.util.concurrent.ExecutionException ee && ee.getCause() != null) return ee.getCause();
    return t;
  }
}
