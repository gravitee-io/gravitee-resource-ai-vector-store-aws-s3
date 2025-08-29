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
import static org.mockito.Mockito.*;

import io.gravitee.resource.ai.vector.store.api.*;
import io.gravitee.resource.ai.vector.store.aws.s3.configuration.*;
import io.gravitee.resource.ai.vector.store.aws.s3.conversions.S3VectorsMetadataCodec;
import io.reactivex.rxjava3.core.*;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.mockito.*;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient;
import software.amazon.awssdk.services.s3vectors.model.*;

class AiVectorStoreAWSS3ResourceTest {

  @Mock
  S3VectorsAsyncClient mockClient;

  AiVectorStoreAWSS3Resource resource;
  AiVectorStoreAWSS3Configuration config;
  AWSS3VectorsConfiguration s3Config;
  AiVectorStoreProperties properties;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    properties = new AiVectorStoreProperties(384, 5, Similarity.COSINE, 0.7f, null, false, false, 1, TimeUnit.HOURS);
    s3Config =
      new AWSS3VectorsConfiguration("bucket", "index", EncryptionType.NONE, null, "us-east-1", "key", "secret", null);
    config = new AiVectorStoreAWSS3Configuration(properties, s3Config);
    resource = spy(new AiVectorStoreAWSS3Resource());
    resource.properties = properties;
    resource.awsS3VectorsConfiguration = s3Config;
    doReturn(config).when(resource).configuration();
    resource.s3VectorsClient = mockClient;
    // Mock putVectors, queryVectors, deleteVectors for both overloads
    when(mockClient.putVectors(any(PutVectorsRequest.class)))
      .thenAnswer(invocation -> CompletableFuture.completedFuture(mock(PutVectorsResponse.class)));

    when(mockClient.queryVectors(any(QueryVectorsRequest.class)))
      .thenAnswer(invocation -> {
        QueryVectorsResponse response = mock(QueryVectorsResponse.class);
        QueryOutputVector output = mock(QueryOutputVector.class);
        when(output.distance()).thenReturn(0.1f);
        when(output.key()).thenReturn("id");
        when(output.metadata()).thenReturn(null);
        when(response.vectors()).thenReturn(List.of(output));
        return CompletableFuture.completedFuture(response);
      });

    when(mockClient.deleteVectors(any(DeleteVectorsRequest.class)))
      .thenAnswer(invocation -> CompletableFuture.completedFuture(mock(DeleteVectorsResponse.class)));
  }

  @Test
  void testDoStartActivatesResource() throws Exception {
    doReturn(Single.just(true)).when(resource).indexExists();
    doReturn(Single.just(true)).when(resource).bucketExists(anyString());
    doReturn(Completable.complete()).when(resource).ensureBucketAndIndex();
    resource.doStart();
    long start = System.currentTimeMillis();
    while (!resource.activated.get() && System.currentTimeMillis() - start < 2000) {
      Thread.sleep(10);
    }
    assertTrue(resource.activated.get(), "Resource should be activated after doStart completes");
  }

  @Test
  void testDoStopClosesClient() throws Exception {
    resource.s3VectorsClient = mockClient;
    resource.doStop();
    verify(mockClient).close();
  }

  @Test
  void testAddReturnsCompletableWhenActivated() {
    resource.activated.set(true);
    VectorEntity entity = new VectorEntity(
      "vec-test-activated",
      null,
      new float[] { 1f, 2f, 3f },
      Map.of(),
      System.currentTimeMillis()
    );
    PutVectorsResponse resp = mock(PutVectorsResponse.class);
    CompletableFuture<PutVectorsResponse> fut = CompletableFuture.completedFuture(resp);
    when(mockClient.putVectors(any(PutVectorsRequest.class))).thenReturn(fut);
    Completable result = resource.add(entity);
    TestObserver<Void> observer = result.test();
    observer.assertComplete();
  }

  @Test
  void testAddReturnsCompleteIfNotActivated() {
    resource.activated.set(false);
    resource.properties = properties;
    resource.awsS3VectorsConfiguration = s3Config;
    VectorEntity entity = new VectorEntity("text", new float[] { 1f, 2f, 3f }, Map.of());
    Completable result = resource.add(entity);
    TestObserver<Void> observer = result.test();
    observer.assertComplete();
  }

  @Test
  void testFindRelevantReturnsFlowable() {
    resource.activated.set(true);
    resource.properties = properties;
    resource.awsS3VectorsConfiguration = s3Config;
    VectorEntity query = new VectorEntity("id", new float[] { 1f, 2f, 3f }, Map.of());
    QueryVectorsResponse response = mock(QueryVectorsResponse.class);
    QueryOutputVector output = mock(QueryOutputVector.class);
    when(output.distance()).thenReturn(0.1f);
    when(output.key()).thenReturn("id");
    when(output.metadata()).thenReturn(null);
    when(response.vectors()).thenReturn(List.of(output));
    CompletableFuture<QueryVectorsResponse> fut = CompletableFuture.completedFuture(response);
    when(mockClient.queryVectors(any(QueryVectorsRequest.class))).thenReturn(fut);
    Flowable<VectorResult> flow = resource.findRelevant(query);
    List<VectorResult> results = flow.toList().blockingGet();
    assertFalse(results.isEmpty());
    assertEquals("id", results.getFirst().entity().id());
  }

  @Test
  void testFindRelevantReturnsEmptyIfNotActivated() {
    resource.activated.set(false);
    resource.properties = properties;
    resource.awsS3VectorsConfiguration = s3Config;
    VectorEntity query = new VectorEntity("id", new float[] { 1f, 2f, 3f }, Map.of());
    Flowable<VectorResult> flow = resource.findRelevant(query);
    List<VectorResult> results = flow.toList().blockingGet();
    assertTrue(results.isEmpty());
  }

  @Test
  void testRemoveCallsDeleteVectors() {
    resource.activated.set(true);
    resource.properties = properties;
    resource.awsS3VectorsConfiguration = s3Config;
    VectorEntity entity = new VectorEntity("id", new float[] { 1f, 2f, 3f }, Map.of());
    DeleteVectorsResponse resp = mock(DeleteVectorsResponse.class);
    CompletableFuture<DeleteVectorsResponse> fut = CompletableFuture.completedFuture(resp);
    when(mockClient.deleteVectors(any(DeleteVectorsRequest.class))).thenReturn(fut);
    resource.remove(entity);
    verify(mockClient).deleteVectors(any(DeleteVectorsRequest.class));
  }

  @Test
  void testRemoveDoesNothingIfNotActivated() {
    resource.activated.set(false);
    resource.properties = properties;
    resource.awsS3VectorsConfiguration = s3Config;
    VectorEntity entity = new VectorEntity("id", new float[] { 1f, 2f, 3f }, Map.of());
    resource.remove(entity);
    verify(mockClient, never()).deleteVectors(any(DeleteVectorsRequest.class));
  }

  @Test
  void testAddIncludesRetrievalContextKeyIfPresent() {
    resource.activated.set(true);
    resource.properties = properties;
    resource.awsS3VectorsConfiguration = s3Config;
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("retrieval_context_key", "tenant-xyz");
    VectorEntity entity = new VectorEntity("id", null, new float[] { 1f, 2f, 3f }, metadata, System.currentTimeMillis());
    PutVectorsResponse resp = mock(PutVectorsResponse.class);
    CompletableFuture<PutVectorsResponse> fut = CompletableFuture.completedFuture(resp);
    ArgumentCaptor<PutVectorsRequest> captor = ArgumentCaptor.forClass(PutVectorsRequest.class);
    when(mockClient.putVectors(any(PutVectorsRequest.class))).thenReturn(fut);
    resource.add(entity).blockingAwait();
    verify(mockClient).putVectors(captor.capture());
    PutVectorsRequest req = captor.getValue();
    PutInputVector putVector = req.vectors().getFirst();
    Document doc = putVector.metadata();
    assertTrue(doc.isMap());
    assertEquals("tenant-xyz", doc.asMap().get("retrieval_context_key").asString());
  }

  @Test
  void testMetadataRoundTripConversion() {
    // Complex metadata example
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("status", 200);
    metadata.put("success", true);
    metadata.put("message", "OK");
    metadata.put("headers", Map.of("Content-Type", List.of("application/json"), "X-Foo", List.of("bar", "baz")));
    metadata.put("tags", List.of("api", "test", 123));

    // Convert to Document using codec
    Document doc = S3VectorsMetadataCodec.toS3Metadata(metadata, false);
    assertTrue(doc.isMap());

    // Convert back to Map<String, Object> using codec
    Map<String, Object> resultMap = S3VectorsMetadataCodec.fromS3Metadata(doc);

    // Check primitive values
    assertEquals(200, resultMap.get("status"));
    assertEquals(true, resultMap.get("success"));
    assertEquals("OK", resultMap.get("message"));

    // Check headers
    Object headersObj = resultMap.get("headers");
    assertInstanceOf(Map.class, headersObj);
    Map<?, ?> headersMap = (Map<?, ?>) headersObj;
    assertEquals(List.of("application/json"), headersMap.get("Content-Type"));
    assertEquals(List.of("bar", "baz"), headersMap.get("X-Foo"));

    // Check tags
    Object tagsObj = resultMap.get("tags");
    assertInstanceOf(List.class, tagsObj);
    List<?> tagsList = (List<?>) tagsObj;
    assertEquals(List.of("api", "test", 123), tagsList);
  }

  @Test
  void testNumberTypeRoundTripConversion() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("intVal", 42);
    metadata.put("longVal", 1234567890123L);
    metadata.put("doubleVal", 3.14159);
    metadata.put("floatVal", 2.71828f);
    metadata.put("shortVal", (short) 7);
    metadata.put("byteVal", (byte) 8);
    metadata.put("negInt", -99);
    metadata.put("bigLong", 2147483648L); // just above Integer.MAX_VALUE
    metadata.put("negDouble", -0.12345);

    Document doc = S3VectorsMetadataCodec.toS3Metadata(metadata, false);
    assertTrue(doc.isMap());

    Map<String, Object> resultMap = S3VectorsMetadataCodec.fromS3Metadata(doc);

    assertEquals(42, resultMap.get("intVal"));
    assertEquals(1234567890123L, resultMap.get("longVal"));
    assertEquals(3.14159, ((Number) resultMap.get("doubleVal")).doubleValue(), 0.00001);
    assertEquals(2.71828, ((Number) resultMap.get("floatVal")).doubleValue(), 0.00001);
    // Shorts and bytes are restored as int
    assertEquals(7, resultMap.get("shortVal"));
    assertEquals(8, resultMap.get("byteVal"));
    assertEquals(-99, resultMap.get("negInt"));
    assertEquals(2147483648L, resultMap.get("bigLong"));
    assertEquals(-0.12345, ((Number) resultMap.get("negDouble")).doubleValue(), 0.00001);
  }
}
