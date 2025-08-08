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
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionByDefault;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionConfiguration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule;
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient;
import software.amazon.awssdk.services.s3vectors.model.CreateIndexRequest;
import software.amazon.awssdk.services.s3vectors.model.DeleteVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.MetadataConfiguration;
import software.amazon.awssdk.services.s3vectors.model.PutInputVector;
import software.amazon.awssdk.services.s3vectors.model.PutVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.S3VectorsException;
import software.amazon.awssdk.services.s3vectors.model.VectorData;

/**
 * @author Derek Thompson (derek.thompson at graviteesource.com)
 * @author GraviteeSource Team
 */
@Slf4j
public class AiVectorStoreAWSS3Resource extends AiVectorStoreResource<AiVectorStoreAWSS3Configuration> {

  private AWSS3Configuration awsS3Config;
  private AiVectorStoreProperties properties;
  private S3VectorsAsyncClient s3VectorsClient;
  private S3AsyncClient s3AsyncClient;

  private final AtomicBoolean activated = new AtomicBoolean(false);

  @Override
  public void doStart() throws Exception {
    super.doStart();

    properties = super.configuration().properties();
    awsS3Config = super.configuration().awsS3Configuration();

    // Initialize S3AsyncClient for bucket operations
    var s3Builder = S3AsyncClient.builder().region(Region.of(awsS3Config.region()));
    s3Builder.credentialsProvider(
      buildAwsCredentialsProvider(awsS3Config.awsAccessKeyId(), awsS3Config.awsSecretAccessKey(), awsS3Config.sessionToken())
    );
    s3AsyncClient = s3Builder.build();

    // Initialize S3VectorsAsyncClient for vector index operations
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
      .doOnError(error -> {
        log.error("Error ensuring AWS S3 bucket/index", error);
        activated.set(false);
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

    List<Float> vectorList = toFloatList(vectorEntity.vector());
    VectorData vectorData = VectorData.fromFloat32(vectorList);
    PutInputVector putVector = PutInputVector.builder().key(vectorEntity.id()).data(vectorData).build();
    PutVectorsRequest putRequest = PutVectorsRequest
      .builder()
      .vectorBucketName(awsS3Config.vectorBucketName())
      .indexName(awsS3Config.vectorIndexName())
      .vectors(putVector)
      .build();
    return Completable
      .create(emitter ->
        s3VectorsClient
          .putVectors(putRequest)
          .whenComplete((result, error) -> {
            if (error != null) {
              emitter.onError(error);
            } else {
              emitter.onComplete();
            }
          })
      )
      .doOnComplete(() -> log.debug("Vector {} put to AWS S3 Vectors.", vectorEntity.id()));
  }

  @Override
  public Flowable<VectorResult> findRelevant(VectorEntity queryEntity) {
    if (!activated.get()) {
      logNotActivated("findRelevant");
      return Flowable.empty();
    }
    List<Float> vectorList = toFloatList(queryEntity.vector());

    var queryRequest = QueryVectorsRequest
      .builder()
      .vectorBucketName(awsS3Config.vectorBucketName())
      .indexName(awsS3Config.vectorIndexName())
      .queryVector(VectorData.fromFloat32(vectorList))
      .topK(properties.maxResults())
      .returnDistance(true)
      .returnMetadata(true)
      .build();

    return Flowable
      .<VectorResult>create(
        emitter ->
          s3VectorsClient
            .queryVectors(queryRequest)
            .whenComplete((response, error) -> {
              if (error != null) {
                emitter.onError(error);
              } else {
                for (var result : response.vectors()) {
                  Map<String, Object> metadata = new java.util.HashMap<>();
                  if (result.metadata() != null) {
                    metadata.putAll(result.metadata().asMap());
                  }
                  String text = metadata.containsKey("text") ? metadata.get("text").toString() : null;
                  metadata.remove("text");
                  metadata.remove("vector");
                  float score = normalizeScore(result.distance());
                  VectorResult vectorResult = new VectorResult(new VectorEntity(result.key(), text, metadata), score);
                  if (vectorResult.score() >= properties.threshold()) {
                    emitter.onNext(vectorResult);
                  }
                }
                emitter.onComplete();
              }
            }),
        BackpressureStrategy.BUFFER
      )
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
    s3VectorsClient
      .deleteVectors(deleteRequest)
      .whenComplete((resp, err) -> {
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
    return Single.<Boolean>create(emitter ->
      s3VectorsClient
        .getIndex(builder ->
          builder.vectorBucketName(awsS3Config.vectorBucketName()).indexName(awsS3Config.vectorIndexName())
        )
        .whenComplete((result, error) -> {
          if (error != null) {
            if (error instanceof S3VectorsException s3ve && s3ve.statusCode() == 404) {
              log.warn("Index does not exist: {}", awsS3Config.vectorIndexName());
              emitter.onSuccess(false);
            } else {
              log.error("Error checking index existence: {}", error.getMessage());
              emitter.onError(error);
            }
          } else {
            log.debug("Index exists: {}", awsS3Config.vectorIndexName());
            emitter.onSuccess(true);
          }
        })
    );
  }

  private Single<Boolean> bucketExists(String bucketName) {
    return Single.<Boolean>create(emitter ->
      s3AsyncClient
        .headBucket(HeadBucketRequest.builder().bucket(bucketName).build())
        .whenComplete((result, error) -> {
          if (error != null) {
            if (error instanceof S3Exception s3e && s3e.statusCode() == 404) {
              log.warn("Bucket does not exist: {}", bucketName);
              emitter.onSuccess(false);
            } else {
              log.error("Error checking bucket existence: {}", error.getMessage());
              emitter.onError(error);
            }
          } else {
            log.debug("Bucket exists: {}", bucketName);
            emitter.onSuccess(true);
          }
        })
    );
  }

  /* TODO: Look at this one, not sure about the using flatMapCompletable here
   */
  private Completable ensureBucketAndIndex() {
    return bucketExists(awsS3Config.vectorBucketName())
      .flatMapCompletable(exists -> exists ? Completable.complete() : createBucket())
      .andThen(indexExists().flatMapCompletable(exists -> exists ? Completable.complete() : createIndex()));
  }

  private Completable createBucket() {
    CreateBucketRequest.Builder builder = CreateBucketRequest.builder().bucket(awsS3Config.vectorBucketName());
    if (awsS3Config.region() != null) {
      builder.createBucketConfiguration(b -> b.locationConstraint(awsS3Config.region()));
    }
    return Completable.create(emitter ->
      s3AsyncClient
        .createBucket(builder.build())
        .whenComplete((result, error) -> {
          if (error != null) {
            log.error("Failed to create bucket {}: {}", awsS3Config.vectorBucketName(), error.getMessage());
            emitter.onError(error);
          } else {
            log.info("Created S3 bucket: {}", awsS3Config.vectorBucketName());
            // Check and apply SSE-KMS encryption if needed
            if (
              "SSE-KMS".equalsIgnoreCase(awsS3Config.encryption()) &&
              awsS3Config.kmsKeyId() != null &&
              !awsS3Config.kmsKeyId().isBlank()
            ) {
              ServerSideEncryptionByDefault sseByDefault = ServerSideEncryptionByDefault
                .builder()
                .sseAlgorithm("aws:kms")
                .kmsMasterKeyID(awsS3Config.kmsKeyId())
                .build();
              ServerSideEncryptionRule sseRule = ServerSideEncryptionRule
                .builder()
                .applyServerSideEncryptionByDefault(sseByDefault)
                .build();
              ServerSideEncryptionConfiguration sseConfig = ServerSideEncryptionConfiguration
                .builder()
                .rules(sseRule)
                .build();
              PutBucketEncryptionRequest encryptionRequest = PutBucketEncryptionRequest
                .builder()
                .bucket(awsS3Config.vectorBucketName())
                .serverSideEncryptionConfiguration(sseConfig)
                .build();
              s3AsyncClient
                .putBucketEncryption(encryptionRequest)
                .whenComplete((encResult, encError) -> {
                  if (encError != null) {
                    log.error(
                      "Failed to set SSE-KMS encryption for bucket {}: {}",
                      awsS3Config.vectorBucketName(),
                      encError.getMessage()
                    );
                    emitter.onError(encError);
                  } else {
                    log.info("Set SSE-KMS encryption for bucket: {}", awsS3Config.vectorBucketName());
                    emitter.onComplete();
                  }
                });
            } else {
              emitter.onComplete();
            }
          }
        })
    );
  }

  private Completable createIndex() {
    MetadataConfiguration metadataConfig = MetadataConfiguration
      .builder()
      .nonFilterableMetadataKeys(awsS3Config.metadataSchema().nonFilterable())
      .build();

    CreateIndexRequest.Builder builder = CreateIndexRequest
      .builder()
      .vectorBucketName(awsS3Config.vectorBucketName())
      .indexName(awsS3Config.vectorIndexName())
      .dimension(properties.embeddingSize())
      .distanceMetric(awsS3Config.distanceMetric().name())
      .metadataConfiguration(metadataConfig);

    return Completable.create(emitter ->
      s3VectorsClient
        .createIndex(builder.build())
        .whenComplete((resp, err) -> {
          if (err != null) {
            log.warn("Index may already exist or could not be created: {}", err.getMessage());
            emitter.onError(err);
          } else {
            log.debug("CreateIndexResponse: {}", resp);
            emitter.onComplete();
          }
        })
    );
  }

  private float normalizeScore(float score) {
    return switch (properties.similarity()) {
      case EUCLIDEAN -> 2 / (2 + score);
      case COSINE, DOT -> (2 - score) / 2;
    };
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
}
