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
import io.gravitee.resource.ai.vector.store.aws.s3.configuration.AWSS3Configuration;
import io.gravitee.resource.ai.vector.store.aws.s3.configuration.AiVectorStoreAWSS3Configuration;
import io.gravitee.resource.ai.vector.store.aws.s3.configuration.EncryptionType;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  private AWSS3Configuration awsS3Config;
  private AiVectorStoreProperties properties;
  private S3VectorsAsyncClient s3VectorsClient;

  private final AtomicBoolean activated = new AtomicBoolean(false);

  @Override
  public void doStart() throws Exception {
    super.doStart();

    properties = super.configuration().properties();
    awsS3Config = super.configuration().awsS3Configuration();

    var builder = S3VectorsAsyncClient.builder().region(Region.of(awsS3Config.region()));
    builder.credentialsProvider(
      buildAwsCredentialsProvider(awsS3Config.awsAccessKeyId(), awsS3Config.awsSecretAccessKey(), awsS3Config.sessionToken())
    );
    s3VectorsClient = builder.build();

    ensureBucketAndIndex()
      .subscribeOn(Schedulers.io())
      .doOnComplete(() -> {
        log.debug("AWS S3 bucket and Vectors index ready.");
        activated.set(true);
        if (properties.readOnly()) {
          logReadOnly("initialization");
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
  public Completable add(VectorEntity vectorEntity) {
    if (!activated.get()) {
      logNotActivated("add");
      return Completable.complete();
    }
    if (properties.readOnly()) {
      logReadOnly("add");
      return Completable.complete();
    }

    return Completable
      .defer(() -> {
        List<Float> vectorList = toFloatList(vectorEntity.vector());
        VectorData vectorData = VectorData.fromFloat32(vectorList);
        PutInputVector putVector = PutInputVector.builder().key(vectorEntity.id()).data(vectorData).build();
        PutVectorsRequest putRequest = PutVectorsRequest
          .builder()
          .vectorBucketName(awsS3Config.vectorBucketName())
          .indexName(awsS3Config.vectorIndexName())
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
      logNotActivated("findRelevant");
      return Flowable.empty();
    }
    return Flowable
      .defer(() -> {
        List<Float> vectorList = toFloatList(queryEntity.vector());
        QueryVectorsRequest req = QueryVectorsRequest
          .builder()
          .vectorBucketName(awsS3Config.vectorBucketName())
          .indexName(awsS3Config.vectorIndexName())
          .queryVector(VectorData.fromFloat32(vectorList))
          .topK(properties.maxResults())
          .returnDistance(true)
          .returnMetadata(true)
          .build();

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
            String text = (String) metadata.remove("text");
            metadata.remove("vector");
            float score = normalizeScore(result.distance());
            return new VectorResult(new VectorEntity(result.key(), text, metadata), score);
          })
          .filter(vr -> vr.score() >= properties.threshold());
      })
      .sorted((a, b) -> Float.compare(b.score(), a.score()));
  }

  @Override
  public void remove(VectorEntity vectorEntity) {
    if (!activated.get()) {
      logNotActivated("remove");
      return;
    }
    if (properties.readOnly()) {
      logReadOnly("remove");
      return;
    }
    DeleteVectorsRequest deleteRequest = DeleteVectorsRequest
      .builder()
      .vectorBucketName(awsS3Config.vectorBucketName())
      .indexName(awsS3Config.vectorIndexName())
      .keys(vectorEntity.id())
      .build();

    var fut = s3VectorsClient.deleteVectors(deleteRequest);
    fut.whenComplete((resp, err) -> {
      if (err != null) {
        log.error("Error removing vector {} from AWS S3 Vectors", vectorEntity.id(), err);
      } else {
        log.debug("Vector {} removed from AWS S3 Vectors.", vectorEntity.id());
      }
    });
  }

  // --- Private helper methods below ---

  private AwsCredentialsProvider buildAwsCredentialsProvider(
    String accessKeyId,
    String secretAccessKey,
    String sessionToken
  ) {
    if (sessionToken != null && !sessionToken.isEmpty()) {
      return StaticCredentialsProvider.create(AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken));
    } else {
      return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
    }
  }

  private Single<Boolean> indexExists() {
    return Single.defer(() -> {
      var fut = s3VectorsClient.getIndex(b ->
        b.vectorBucketName(awsS3Config.vectorBucketName()).indexName(awsS3Config.vectorIndexName())
      );

      return Single
        .fromCompletionStage(fut)
        .map(resp -> true)
        .onErrorResumeNext(err -> {
          Throwable e = unwrapCompletion(err);
          if (e instanceof S3VectorsException s3ve && s3ve.statusCode() == 404) {
            log.warn("Index does not exist: {}", awsS3Config.vectorIndexName());
            return Single.just(false);
          }
          log.error("Error checking index existence: {}", e.getMessage());
          return Single.error(e);
        })
        .doOnDispose(() -> fut.cancel(true));
    });
  }

  private Single<Boolean> bucketExists(String bucketName) {
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

  private Completable ensureBucketAndIndex() {
    return bucketExists(awsS3Config.vectorBucketName())
      .flatMapCompletable(exists -> exists ? Completable.complete() : createBucket())
      .andThen(indexExists().flatMapCompletable(exists -> exists ? Completable.complete() : createIndex()));
  }

  private Completable createBucket() {
    return Completable.defer(() -> {
      CreateVectorBucketRequest.Builder builder = CreateVectorBucketRequest
        .builder()
        .vectorBucketName(awsS3Config.vectorBucketName());

      if (awsS3Config.encryption() != null && awsS3Config.encryption() != EncryptionType.NONE) {
        EncryptionConfiguration.Builder encBuilder = EncryptionConfiguration.builder();
        switch (awsS3Config.encryption()) {
          case SSE_KMS -> {
            encBuilder.sseType(SseType.AWS_KMS);
            if (awsS3Config.kmsKeyId() != null && !awsS3Config.kmsKeyId().isEmpty()) {
              encBuilder.kmsKeyArn(awsS3Config.kmsKeyId());
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
        .doOnComplete(() -> log.info("Created S3 Vectors bucket: {}", awsS3Config.vectorBucketName()))
        .doOnError(err -> log.error("Failed to create bucket {}: {}", awsS3Config.vectorBucketName(), err.getMessage(), err)
        );
    });
  }

  private Completable createIndex() {
    return Completable.defer(() -> {
      MetadataConfiguration metadataConfig = MetadataConfiguration
        .builder()
        .nonFilterableMetadataKeys(awsS3Config.metadataSchema().nonFilterable())
        .build();

      CreateIndexRequest req = CreateIndexRequest
        .builder()
        .vectorBucketName(awsS3Config.vectorBucketName())
        .indexName(awsS3Config.vectorIndexName())
        .dimension(properties.embeddingSize())
        .distanceMetric(awsS3Config.distanceMetric().name())
        .metadataConfiguration(metadataConfig)
        .build();

      var fut = s3VectorsClient.createIndex(req);
      return Completable
        .fromCompletionStage(fut)
        .doOnDispose(() -> fut.cancel(true))
        .doOnComplete(() -> log.debug("Index created: {}", awsS3Config.vectorIndexName()))
        .doOnError(err -> log.warn("Index may already exist or could not be created: {}", err.getMessage(), err));
    });
  }

  private float normalizeScore(float score) {
    return switch (properties.similarity()) {
      case EUCLIDEAN -> 2 / (2 + score);
      case COSINE, DOT -> (2 - score) / 2;
    };
  }

  private static Object extractValue(Document v) {
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

  private void logReadOnly(String operation) {
    log.warn(
      "Resource is read-only. Skipping {} operation for bucket {} and index {}.",
      operation,
      awsS3Config.vectorBucketName(),
      awsS3Config.vectorIndexName()
    );
  }

  private void logNotActivated(String operation) {
    log.warn(
      "Resource not activated. Skipping {} operation for bucket {} and index {}.",
      operation,
      awsS3Config.vectorBucketName(),
      awsS3Config.vectorIndexName()
    );
  }

  private List<Float> toFloatList(float[] arr) {
    List<Float> list = new ArrayList<>(arr.length);
    for (float v : arr) list.add(v);
    return list;
  }

  private static Throwable unwrapCompletion(Throwable t) {
    if (t instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null) return ce.getCause();
    if (t instanceof java.util.concurrent.ExecutionException ee && ee.getCause() != null) return ee.getCause();
    return t;
  }
}
