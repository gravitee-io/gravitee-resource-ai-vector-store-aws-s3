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
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    this.s3VectorsClient = builder.build();
    if (properties.readOnly()) {
      log.debug("AiVectorStoreAWSS3Resource is read-only");
    } else {
      ensureBucketAndIndex()
        .subscribeOn(Schedulers.io())
        .doOnComplete(() -> log.debug("AWS S3 bucket and Vectors index ready."))
        .doOnError(error -> log.error("Error ensuring AWS S3 bucket/index", error))
        .subscribe();
    }
  }

  @Override
  public Completable add(VectorEntity vectorEntity) {
    if (properties.readOnly()) {
      log.debug("AiVectorStoreAWSS3Resource.add is read-only");
      return Completable.complete();
    }

    float[] vectorArr = vectorEntity.vector();
    List<Float> vectorList = new ArrayList<>(vectorArr.length);
    for (float v : vectorArr) vectorList.add(v);
    VectorData vectorData = VectorData.fromFloat32(vectorList);
    PutInputVector putVector = PutInputVector.builder().key(vectorEntity.id()).data(vectorData).build();
    PutVectorsRequest putRequest = PutVectorsRequest
      .builder()
      .vectorBucketName(awsS3Config.vectorBucketName())
      .indexName(awsS3Config.vectorIndexName())
      .vectors(putVector)
      .build();
    return Completable
      .fromFuture(s3VectorsClient.putVectors(putRequest))
      .doOnComplete(() -> log.debug("Vector {} put to AWS S3 Vectors.", vectorEntity.id()));
  }

  @Override
  public Flowable<VectorResult> findRelevant(VectorEntity queryEntity) {
    float[] vectorArr = queryEntity.vector();
    List<Float> vectorList = new ArrayList<>(vectorArr.length);
    for (float v : vectorArr) vectorList.add(v);

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
      .fromFuture(s3VectorsClient.queryVectors(queryRequest))
      .flatMap(response -> Flowable.fromIterable(response.vectors()))
      .map(result -> {
        Map<String, Object> metadata = new java.util.HashMap<>();
        if (result.metadata() != null) {
          metadata.putAll(result.metadata().asMap());
        }
        String text = metadata.containsKey("text") ? metadata.get("text").toString() : null;
        metadata.remove("text");
        metadata.remove("vector");
        float score = normalizeScore(result.distance());
        return new VectorResult(new VectorEntity(result.key(), text, metadata), score);
      })
      .filter(result -> result.score() >= properties.threshold())
      .sorted((a, b) -> Float.compare(b.score(), a.score()));
  }

  @Override
  public void remove(VectorEntity vectorEntity) {
    if (properties.readOnly()) {
      log.debug("AiVectorStoreAWSS3Resource.remove is read-only");
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

  private Completable ensureBucketAndIndex() {
    return bucketExists(awsS3Config.vectorBucketName())
      .onErrorResumeNext(e -> {
        if (e instanceof S3Exception s3e && s3e.statusCode() == 404) {
          return createBucket(awsS3Config.vectorBucketName());
        }
        return Completable.error(e);
      })
      .andThen(createIndex().ignoreElement());
  }

  private Completable bucketExists(String bucketName) {
    return Completable.fromFuture(s3AsyncClient.headBucket(HeadBucketRequest.builder().bucket(bucketName).build()));
  }

  private Completable createBucket(String bucketName) {
    CreateBucketRequest.Builder builder = CreateBucketRequest.builder().bucket(bucketName);
    if (awsS3Config.region() != null) {
      builder.createBucketConfiguration(b -> b.locationConstraint(awsS3Config.region()));
    }
    // Create the bucket first
    Completable create = Completable
      .fromFuture(s3AsyncClient.createBucket(builder.build()))
      .doOnComplete(() -> log.info("Created S3 bucket: {}", bucketName));

    // If SSE-KMS is requested, set default encryption after bucket creation
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
      ServerSideEncryptionConfiguration sseConfig = ServerSideEncryptionConfiguration.builder().rules(sseRule).build();
      PutBucketEncryptionRequest encryptionRequest = PutBucketEncryptionRequest
        .builder()
        .bucket(bucketName)
        .serverSideEncryptionConfiguration(sseConfig)
        .build();
      return create.andThen(
        Completable
          .fromFuture(s3AsyncClient.putBucketEncryption(encryptionRequest))
          .doOnComplete(() -> log.info("Set SSE-KMS encryption for bucket: {}", bucketName))
      );
    }
    return create;
  }

  private Single<Boolean> createIndex() {
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

    return Single
      .fromFuture(s3VectorsClient.createIndex(builder.build()))
      .map(resp -> {
        log.debug("CreateIndexResponse: {}", resp);
        return true;
      })
      .onErrorReturn(e -> {
        log.warn("Index may already exist or could not be created: {}", e.getMessage());
        return false;
      });
  }

  private float normalizeScore(float score) {
    return switch (this.properties.similarity()) {
      case EUCLIDEAN -> 2 / (2 + score);
      case COSINE, DOT -> (2 - score) / 2;
    };
  }
}
