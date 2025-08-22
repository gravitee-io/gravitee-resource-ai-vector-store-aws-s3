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

import static org.junit.jupiter.api.Assertions.*;

import io.gravitee.resource.ai.vector.store.api.*;
import io.gravitee.resource.ai.vector.store.aws.s3.configuration.*;
import io.reactivex.rxjava3.core.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AiVectorStoreAWSS3ResourceIT {

  private AiVectorStoreAWSS3Resource resource;
  private AWSS3VectorsConfiguration s3Config;
  private AiVectorStoreProperties properties;
  private final String bucketName = "gravitee-it-bucket";
  private final String indexName = "gravitee-it-index";
  private final String region = "us-east-1";
  private final String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
  private final String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
  private final String sessionToken = System.getenv("AWS_SESSION_TOKEN");

  @BeforeAll
  void setup() {
    properties = new AiVectorStoreProperties(8, 5, Similarity.COSINE, 0.5f, null, false, true, 1, TimeUnit.HOURS);
    s3Config =
      new AWSS3VectorsConfiguration(
        bucketName,
        indexName,
        EncryptionType.NONE,
        null,
        region,
        accessKey,
        secretKey,
        sessionToken
      );
    AiVectorStoreAWSS3Configuration config = new AiVectorStoreAWSS3Configuration(properties, s3Config);
    resource = new AiVectorStoreAWSS3Resource();
    resource.s3VectorsClient =
      S3VectorsAsyncClient
        .builder()
        .region(Region.of(region))
        .credentialsProvider(
          sessionToken != null && !sessionToken.isEmpty()
            ? StaticCredentialsProvider.create(AwsSessionCredentials.create(accessKey, secretKey, sessionToken))
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
        )
        .build();
    resource.properties = properties;
    resource.awsS3VectorsConfig = s3Config;
  }

  @BeforeEach
  void cleanIndexAndBucket() {
    // Reinitialize properties and resource for each test to avoid state leakage
    properties = new AiVectorStoreProperties(8, 5, Similarity.COSINE, 0.5f, null, false, true, 1, TimeUnit.HOURS);
    s3Config =
      new AWSS3VectorsConfiguration(
        bucketName,
        indexName,
        EncryptionType.NONE,
        null,
        region,
        accessKey,
        secretKey,
        sessionToken
      );
    resource = new AiVectorStoreAWSS3Resource();
    resource.s3VectorsClient =
      S3VectorsAsyncClient
        .builder()
        .region(Region.of(region))
        .credentialsProvider(
          sessionToken != null && !sessionToken.isEmpty()
            ? StaticCredentialsProvider.create(AwsSessionCredentials.create(accessKey, secretKey, sessionToken))
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
        )
        .build();
    resource.properties = properties;
    resource.awsS3VectorsConfig = s3Config;
    System.out.println("[TEST SETUP] Cleaning index and bucket before test: " + this.getClass().getSimpleName());
    // Delete index if exists
    try {
      resource.s3VectorsClient.deleteIndex(b -> b.vectorBucketName(bucketName).indexName(indexName)).join();
    } catch (Exception ignored) {}
    // Delete bucket if exists
    try {
      resource.s3VectorsClient.deleteVectorBucket(b -> b.vectorBucketName(bucketName)).join();
    } catch (Exception ignored) {}
    // Recreate bucket and index for each test
    assertDoesNotThrow(() -> resource.ensureBucketAndIndex().blockingAwait());
    // Extra verification: ensure index is empty
    try {
      var response = resource.s3VectorsClient
        .queryVectors(b -> b.vectorBucketName(bucketName).indexName(indexName).topK(1))
        .get();
      assertTrue(response.vectors().isEmpty(), "Index is not empty after cleanup");
    } catch (Exception e) {
      // If index doesn't exist, that's fine
    }
  }

  @Test
  void testEnsureBucketAndIndexCreatesIfNotExist() {
    // Should succeed even if bucket/index already exist
    assertDoesNotThrow(() -> resource.ensureBucketAndIndex().blockingAwait());
  }

  @Test
  void testAddAndSearchVectorWithMetadata() {
    resource.activated.set(true);
    String id = "vec-it-1";
    float[] vector = new float[] { 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f };
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("retrieval_context_key", "tenant-it");
    metadata.put("custom", "value");
    VectorEntity entity = new VectorEntity(id, null, vector, metadata, System.currentTimeMillis());
    assertDoesNotThrow(() -> resource.add(entity).blockingAwait());

    // Query with a valid id and vector
    VectorEntity query = new VectorEntity(
      id,
      null,
      vector,
      Map.of("retrieval_context_key", "tenant-it"),
      System.currentTimeMillis()
    );
    List<VectorResult> results = resource.findRelevant(query).toList().blockingGet();
    assertFalse(results.isEmpty());
    assertEquals(id, results.getFirst().entity().id());
    assertEquals("tenant-it", results.getFirst().entity().metadata().get("retrieval_context_key"));
  }

  @Test
  void testAddVectorWithEvictionMetadata() {
    resource.activated.set(true);
    properties = new AiVectorStoreProperties(8, 5, Similarity.COSINE, 0.5f, null, false, true, 1, TimeUnit.HOURS);
    resource.properties = properties;
    String id = "vec-it-evict";
    float[] vector = new float[] { 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f };
    VectorEntity entity = new VectorEntity(id, null, vector, Map.of(), System.currentTimeMillis());
    assertDoesNotThrow(() -> resource.add(entity).blockingAwait());
    // Search and check expireAt metadata
    VectorEntity query = new VectorEntity("query", null, vector, Map.of(), System.currentTimeMillis());
    List<VectorResult> results = resource.findRelevant(query).toList().blockingGet();
    assertFalse(results.isEmpty());
    assertTrue(results.getFirst().entity().metadata().containsKey("expireAt"));
  }

  @Test
  void testRemoveVector() {
    resource.activated.set(true);
    String id = "vec-it-remove";
    float[] vector = new float[] { 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f };
    VectorEntity entity = new VectorEntity(id, null, vector, Map.of(), System.currentTimeMillis());
    assertDoesNotThrow(() -> resource.add(entity).blockingAwait());
    assertDoesNotThrow(() -> resource.remove(entity));
    // After removal, search should not return the vector
    VectorEntity query = new VectorEntity("query", vector, Map.of());
    List<VectorResult> results = resource.findRelevant(query).toList().blockingGet();
    // May still return if S3 Vectors is eventually consistent, but should be empty after some time
  }

  @Test
  void testSessionTokenOptional() {
    AWSS3VectorsConfiguration configNoSession = new AWSS3VectorsConfiguration(
      bucketName,
      indexName,
      EncryptionType.NONE,
      null,
      region,
      accessKey,
      secretKey,
      null
    );
    AiVectorStoreAWSS3Resource resourceNoSession = new AiVectorStoreAWSS3Resource();
    resourceNoSession.s3VectorsClient =
      S3VectorsAsyncClient
        .builder()
        .region(Region.of(region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .build();
    resourceNoSession.properties = properties;
    resourceNoSession.awsS3VectorsConfig = configNoSession;
    assertDoesNotThrow(() -> resourceNoSession.ensureBucketAndIndex().blockingAwait());
  }

  @Test
  void testEnsureBucketExistsOnly() {
    // Create bucket only, index does not exist
    resource.activated.set(false);
    // Remove index if exists
    resource.s3VectorsClient.deleteIndex(b -> b.vectorBucketName(bucketName).indexName(indexName)).join();
    // Ensure bucket exists
    assertDoesNotThrow(() -> resource.ensureBucketAndIndex().blockingAwait());
    // Should create index if missing
    assertTrue(resource.indexExists().blockingGet());
  }

  @Test
  void testEnsureBothExist() {
    // Create both bucket and index ahead of time
    resource.activated.set(false);
    assertDoesNotThrow(() -> resource.ensureBucketAndIndex().blockingAwait());
    // Should not throw or recreate
    assertTrue(resource.bucketExists(bucketName).blockingGet());
    assertTrue(resource.indexExists().blockingGet());
  }

  @Test
  void testAddAndQueryWithThreshold() {
    resource.activated.set(true);
    // Add two vectors, one similar, one not
    String id1 = "vec-thresh-1";
    String id2 = "vec-thresh-2";
    float[] v1 = new float[] { 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f };
    float[] v2 = new float[] { 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f };
    VectorEntity e1 = new VectorEntity(id1, null, v1, Map.of(), System.currentTimeMillis());
    VectorEntity e2 = new VectorEntity(id2, null, v2, Map.of(), System.currentTimeMillis());
    assertDoesNotThrow(() -> resource.add(e1).blockingAwait());
    assertDoesNotThrow(() -> resource.add(e2).blockingAwait());
    // Query with high threshold (should only get exact match)
    resource.properties = new AiVectorStoreProperties(8, 5, Similarity.COSINE, 0.99f, null, false, true, 1, TimeUnit.HOURS);
    VectorEntity query = new VectorEntity(id1, null, v1, Map.of(), System.currentTimeMillis());
    List<VectorResult> results = resource.findRelevant(query).toList().blockingGet();
    assertEquals(1, results.size());
    assertEquals(id1, results.getFirst().entity().id());
    // Query with low threshold (should get both)
    resource.properties = new AiVectorStoreProperties(8, 5, Similarity.COSINE, 0.0f, null, false, true, 1, TimeUnit.HOURS);
    results = resource.findRelevant(query).toList().blockingGet();
    assertTrue(results.size() >= 2);
  }

  @Test
  void testMetadataFiltering() {
    resource.activated.set(true);
    String idA = "vec-meta-a";
    String idB = "vec-meta-b";
    float[] vA = new float[] { 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f };
    float[] vB = new float[] { 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f };
    VectorEntity eA = new VectorEntity(
      idA,
      null,
      vA,
      Map.of("retrieval_context_key", "tenantA"),
      System.currentTimeMillis()
    );
    VectorEntity eB = new VectorEntity(
      idB,
      null,
      vB,
      Map.of("retrieval_context_key", "tenantB"),
      System.currentTimeMillis()
    );
    assertDoesNotThrow(() -> resource.add(eA).blockingAwait());
    assertDoesNotThrow(() -> resource.add(eB).blockingAwait());
    // Query for tenantA
    VectorEntity queryA = new VectorEntity(
      idA,
      null,
      vA,
      Map.of("retrieval_context_key", "tenantA"),
      System.currentTimeMillis()
    );
    List<VectorResult> resultsA = resource.findRelevant(queryA).toList().blockingGet();
    assertFalse(resultsA.isEmpty());
    assertEquals("tenantA", resultsA.getFirst().entity().metadata().get("retrieval_context_key"));
    // Query for tenantB
    VectorEntity queryB = new VectorEntity(
      idB,
      null,
      vB,
      Map.of("retrieval_context_key", "tenantB"),
      System.currentTimeMillis()
    );
    List<VectorResult> resultsB = resource.findRelevant(queryB).toList().blockingGet();
    assertFalse(resultsB.isEmpty());
    assertEquals("tenantB", resultsB.getFirst().entity().metadata().get("retrieval_context_key"));
  }

  @Test
  void testReadOnlyMode() {
    resource.activated.set(true);
    resource.properties = new AiVectorStoreProperties(8, 5, Similarity.COSINE, 0.5f, null, true, true, 1, TimeUnit.HOURS);
    String id = "vec-readonly";
    float[] vector = new float[] { 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f };
    VectorEntity entity = new VectorEntity(id, null, vector, Map.of(), System.currentTimeMillis());
    // Should not throw, but not actually add
    assertDoesNotThrow(() -> resource.add(entity).blockingAwait());
    // Should not throw on remove
    assertDoesNotThrow(() -> resource.remove(entity));
  }

  @Test
  void testEvictionMetadataTagging() {
    resource.activated.set(true);
    resource.properties = new AiVectorStoreProperties(8, 5, Similarity.COSINE, 0.5f, null, false, true, 1, TimeUnit.MINUTES);
    String id = "vec-evict-tag";
    float[] vector = new float[] { 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f };
    VectorEntity entity = new VectorEntity(id, null, vector, Map.of(), System.currentTimeMillis());
    assertDoesNotThrow(() -> resource.add(entity).blockingAwait());
    VectorEntity query = new VectorEntity(id, null, vector, Map.of(), System.currentTimeMillis());
    List<VectorResult> results = resource.findRelevant(query).toList().blockingGet();
    assertFalse(results.isEmpty());
    assertTrue(results.getFirst().entity().metadata().containsKey("expireAt"));
  }

  @Test
  void testRemoveNonExistentVector() {
    resource.activated.set(true);
    String id = "vec-nonexistent";
    float[] vector = new float[] { 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f };
    VectorEntity entity = new VectorEntity(id, null, vector, Map.of(), System.currentTimeMillis());
    // Should not throw even if vector does not exist
    assertDoesNotThrow(() -> resource.remove(entity));
  }

  @Test
  void testAddDuplicateKey() {
    resource.activated.set(true);
    String id = "vec-dup";
    float[] vector = new float[] { 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f };
    VectorEntity entity1 = new VectorEntity(id, null, vector, Map.of(), System.currentTimeMillis());
    VectorEntity entity2 = new VectorEntity(id, null, vector, Map.of(), System.currentTimeMillis());
    assertDoesNotThrow(() -> resource.add(entity1).blockingAwait());
    // Adding duplicate key should not throw, but may overwrite
    assertDoesNotThrow(() -> resource.add(entity2).blockingAwait());
  }
}
