package io.gravitee.resource.ai.vector.store.aws.s3;

import io.gravitee.resource.ai.vector.store.api.*;
import io.gravitee.resource.ai.vector.store.aws.s3.configuration.*;
import io.reactivex.rxjava3.core.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient;
import software.amazon.awssdk.services.s3vectors.model.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiVectorStoreAWSS3ResourceTest {
    @Mock S3VectorsAsyncClient mockClient;
    AiVectorStoreAWSS3Resource resource;
    AiVectorStoreAWSS3Configuration config;
    AWSS3VectorsConfiguration s3Config;
    AiVectorStoreProperties properties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new AiVectorStoreProperties(384, 5, AiVectorStoreProperties Similarity.COSINE, 0.7f, false, false, 1, TimeUnit.HOURS);
        s3Config = new AWSS3VectorsConfiguration("bucket", "index", EncryptionType.NONE, null, "us-east-1", "key", "secret", null);
        config = new AiVectorStoreAWSS3Configuration(properties, s3Config);
        resource = spy(new AiVectorStoreAWSS3Resource());
        doReturn(config).when(resource).configuration();
        resource.s3VectorsClient = mockClient;
    }

    @Test
    void testDoStartActivatesResource() throws Exception {
        doReturn(Single.just(true)).when(resource).indexExists();
        doReturn(Single.just(true)).when(resource).bucketExists(anyString());
        doReturn(Completable.complete()).when(resource).ensureBucketAndIndex();
        resource.doStart();
        assertTrue(resource.activated.get());
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
        VectorEntity entity = new VectorEntity("id", "text", Map.of(), new float[]{1f,2f,3f});
        PutVectorsResponse resp = mock(PutVectorsResponse.class);
        CompletableFuture<PutVectorsResponse> fut = CompletableFuture.completedFuture(resp);
        when(mockClient.putVectors(any())).thenReturn(fut);
        Completable result = resource.add(entity);
        TestObserver<Void> observer = result.test();
        observer.awaitTerminalEvent();
        observer.assertComplete();
    }

    @Test
    void testAddReturnsCompleteIfNotActivated() {
        resource.activated.set(false);
        VectorEntity entity = new VectorEntity("id", "text", Map.of(), new float[]{1f,2f,3f});
        Completable result = resource.add(entity);
        TestObserver<Void> observer = result.test();
        observer.awaitTerminalEvent();
        observer.assertComplete();
    }

    @Test
    void testFindRelevantReturnsFlowable() {
        resource.activated.set(true);
        VectorEntity query = new VectorEntity("id", "text", Map.of(), new float[]{1f,2f,3f});
        QueryVectorsResponse response = mock(QueryVectorsResponse.class);
        QueryOutputVector output = mock(QueryOutputVector.class);
        when(output.distance()).thenReturn(0.1f);
        when(output.key()).thenReturn("id");
        when(output.metadata()).thenReturn(null);
        when(response.vectors()).thenReturn(List.of(output));
        CompletableFuture<QueryVectorsResponse> fut = CompletableFuture.completedFuture(response);
        when(mockClient.queryVectors(any())).thenReturn(fut);
        Flowable<VectorResult> flow = resource.findRelevant(query);
        List<VectorResult> results = flow.toList().blockingGet();
        assertFalse(results.isEmpty());
        assertEquals("id", results.get(0).entity().id());
    }

    @Test
    void testFindRelevantReturnsEmptyIfNotActivated() {
        resource.activated.set(false);
        VectorEntity query = new VectorEntity("id", "text", Map.of(), new float[]{1f,2f,3f});
        Flowable<VectorResult> flow = resource.findRelevant(query);
        List<VectorResult> results = flow.toList().blockingGet();
        assertTrue(results.isEmpty());
    }

    @Test
    void testRemoveCallsDeleteVectors() {
        resource.activated.set(true);
        VectorEntity entity = new VectorEntity("id", "text", Map.of(), new float[]{1f,2f,3f});
        DeleteVectorsResponse resp = mock(DeleteVectorsResponse.class);
        CompletableFuture<DeleteVectorsResponse> fut = CompletableFuture.completedFuture(resp);
        when(mockClient.deleteVectors(any())).thenReturn(fut);
        resource.remove(entity);
        verify(mockClient).deleteVectors(any());
    }

    @Test
    void testRemoveDoesNothingIfNotActivated() {
        resource.activated.set(false);
        VectorEntity entity = new VectorEntity("id", "text", Map.of(), new float[]{1f,2f,3f});
        resource.remove(entity);
        verify(mockClient, never()).deleteVectors(any());
    }
}
