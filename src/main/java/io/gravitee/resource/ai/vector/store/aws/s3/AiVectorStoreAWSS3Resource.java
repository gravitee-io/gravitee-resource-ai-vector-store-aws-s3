/*
 * Copyright © 2015 The Gravitee team (http://graviteesource.com)
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
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.vertx.rxjava3.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient;
import software.amazon.awssdk.services.s3vectors.model.CreateIndexRequest;
import software.amazon.awssdk.services.s3vectors.model.MetadataConfiguration;

/**
 * @author Derek Thompson (derek.thompson at graviteesource.com)
 * @author GraviteeSource Team
 */
@Slf4j
public class AiVectorStoreAWSS3Resource extends AiVectorStoreResource<AiVectorStoreAWSS3Configuration> {

  private AWSS3Configuration awsS3Config;
  private AiVectorStoreProperties properties;
  private S3VectorsAsyncClient s3VectorsClient;
  private Vertx vertx;

  @Override
  public void doStart() throws Exception {
    super.doStart();
    vertx = getBean(Vertx.class);
    properties = super.configuration().properties();
    awsS3Config = super.configuration().awsS3Configuration();
    // Initialize S3VectorsAsyncClient with credentials
    S3VectorsAsyncClient s3VectorsClientTmp;
    var builder = S3VectorsAsyncClient.builder().region(Region.of(awsS3Config.region()));
    if (awsS3Config.sessionToken() != null && !awsS3Config.sessionToken().isEmpty()) {
      builder.credentialsProvider(
        StaticCredentialsProvider.create(
          AwsSessionCredentials.create(
            awsS3Config.awsAccessKeyId(),
            awsS3Config.awsSecretAccessKey(),
            awsS3Config.sessionToken()
          )
        )
      );
    } else {
      builder.credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(awsS3Config.awsAccessKeyId(), awsS3Config.awsSecretAccessKey())
        )
      );
    }
    s3VectorsClientTmp = builder.build();
    this.s3VectorsClient = s3VectorsClientTmp;
    if (properties.readOnly()) {
      log.debug("AiVectorStoreAWSS3Resource is read-only");
    } else {
      createIndex()
        .subscribeOn(Schedulers.io())
        .subscribe(
          success -> log.debug("AWS S3 Vectors index created or already exists."),
          error -> log.error("Error creating AWS S3 Vectors index", error)
        );
    }
  }

  @Override
  public void doStop() throws Exception {
    super.doStop();
    if (s3VectorsClient != null) {
      s3VectorsClient.close();
      s3VectorsClient = null;
    }
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

  @Override
  public Completable add(VectorEntity vectorEntity) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public Flowable<VectorResult> findRelevant(VectorEntity queryEntity) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public void remove(VectorEntity vectorEntity) {
    throw new UnsupportedOperationException("Not yet implemented");
  }
}
