# Gravitee Resource S3‑Tensors AI Vector Store

This resource provides vector search capabilities using **Amazon S3 Vectors (S3‑Tensors)** as the underlying vector store. It is designed to be integrated into AI pipelines that rely on semantic similarity, retrieval-augmented generation (RAG), or embedding-based search.

Supports advanced features like scalable storage, strong consistency, and fine-grained metadata filtering.

---

## 🔧 Configuration

To use this resource, register it with the following configuration:

```json
{
  "name": "vector-store-aws-s3-vectors-resource",
  "type": "ai-vector-store-aws-s3-vectors",
  "enabled": true,
  "configuration": {
    "properties": {
      "embeddingSize": 1024,
      "maxResults": 10,
      "similarity": "COSINE",
      "threshold": 0.5,
      "readOnly": false
    },
    "s3VectorsConfig": {
      "vectorBucketName": "my-vector-bucket",
      "vectorIndexName": "default-index",
      "encryption": "SSE-S3",
      "kmsKeyId": null,
      "distanceMetric": "COSINE",
      "region": "us-east-1",
      "awsAccessKeyId": "...",
      "awsSecretAccessKey": "...",
      "sessionToken": null
    }
  }
}
```

---

## ⚙️ Key Configuration Options

### Top-Level Properties

| Field            | Description                                                                 |
|------------------|-----------------------------------------------------------------------------|
| `embeddingSize`  | Size of the input embedding vector. Must match the size used by your model. |
| `maxResults`     | Number of top similar vectors to return per query.                          |
| `similarity`     | Similarity function: `COSINE` or `EUCLIDEAN`.                               |
| `threshold`      | Minimum similarity score to return results.                                 |
| `readOnly`       | If `true`, disables writes and only performs queries.                       |
| `allowEviction`  | Enables automatic eviction of stale vectors.                                |
| `evictTime`      | Time after which vectors can be evicted.                                    |
| `evictTimeUnit`  | Time unit for eviction: `SECONDS`, `MINUTES`, or `HOURS`.                    |

---

### S3 Vectors Configuration

| Field               | Description                                              |
|---------------------|----------------------------------------------------------|
| `vectorBucketName`  | S3 vector bucket name (3–63 lowercase, numbers, hyphens) |
| `vectorIndexName`   | Index name within the bucket; immutable after creation   |
| `encryption`        | `"SSE_S3"` or `"SSE_KMS"`                                |
| `kmsKeyId`          | ARN of the KMS key if using `"SSE_KMS"`                  |
| `region`            | AWS region where the bucket/index exist                  |
| `awsAccessKeyId`    | AWS access key ID                                        |
| `awsSecretAccessKey`| AWS secret access key                                    |
| `sessionToken`      | AWS session token (optional)                             |

---

## 🧠 Example Usage Pattern

1. **Create a vector bucket and index** via AWS Console, CLI, or SDK. Set dimensions, distance metric, and non-filterable metadata keys (these are immutable).
2. **Insert vectors** using the PutVectors API (max 500 vectors per call). Each vector has a unique key, float32 embedding matching `dimensions`, and metadata.
3. **Query vectors** using QueryVectors API, optionally filtering on filterable metadata keys, and returning similarity/distance scores and metadata if requested.
4. **Metadata filtering** only applies to keys marked filterable at index creation.

---

## ✅ Features

- Fully managed, no infrastructure to run or patch
- Scales to millions/billions of vectors and thousands of indexes
- Low-cost, up to 90% cheaper than dedicated vector DBs
- Strong read-after-write consistency
- Fine-grained metadata filtering and IAM-based access control
- Seamless integration with Bedrock Knowledge Bases and OpenSearch

---

## 🗃 Supported Similarity Functions

- `COSINE`: Ideal for normalized embeddings.
- `EUCLIDEAN`: Computes L2 distance.
> **Note:** The index’s `distanceMetric` must match your configured `similarity` for correct results.

---

## 🚀 Use Cases

- Retrieval-Augmented Generation (RAG) with Amazon Bedrock
- Semantic search over large document/media sets
- Agent memory/context vector storage
- Multi-tenant indexing (separate buckets or index prefixes)

---

## 📌 Summary

This **S3‑Tensors (Amazon S3 Vectors)** resource delivers a fully managed, scalable, cost-efficient vector store on S3. It is ideal for production-grade RAG and semantic search pipelines, with strong AWS-native integration and metadata support.

---

## 🚀 Example SDK Usage (Java + RxJava Maybe + AWS SDK v2)

```java
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient;
import software.amazon.awssdk.services.s3vectors.model.PutInputVector;
import software.amazon.awssdk.services.s3vectors.model.PutVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.QueryOutputVector;
import io.reactivex.rxjava3.core.Maybe;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class S3VectorsRxJavaExample {

    private final S3VectorsAsyncClient client;
    private final String vectorBucketName = "my-vector-bucket";
    private final String vectorIndexName  = "default-index";

    public S3VectorsRxJavaExample(S3VectorsAsyncClient client) {
        this.client = client;
    }

    public Maybe<Void> putVectors(List<PutInputVector> vectors) {
        CompletableFuture<Void> fut = client
            .putVectors(PutVectorsRequest.builder()
                .vectorBucketName(vectorBucketName)
                .indexName(vectorIndexName)
                .vectors(vectors)
                .build())
            .thenApply(resp -> null);
        return Maybe.fromFuture(fut);
    }

    public Maybe<List<QueryOutputVector>> queryNearest(
        List<Float> embeddingQuery,
        int topK,
        Map<String, String> metadataFilter,
        boolean returnMetadata,
        boolean returnDistance
    ) {
        QueryVectorsRequest.Builder qb = QueryVectorsRequest.builder()
            .vectorBucketName(vectorBucketName)
            .indexName(vectorIndexName)
            .queryVector(q -> q.float32(embeddingQuery))
            .topK(topK)
            .returnMetadata(returnMetadata)
            .returnDistance(returnDistance);

        if (metadataFilter != null && !metadataFilter.isEmpty()) {
            qb.filter(metadataFilter);
        }

        CompletableFuture<List<QueryOutputVector>> fut = client
            .queryVectors(qb.build())
            .thenApply(resp -> resp.results());

        return Maybe.fromFuture(fut);
    }

    public static void main(String[] args) {
        S3VectorsAsyncClient client = S3VectorsAsyncClient.builder()
            .region(software.amazon.awssdk.regions.Region.US_EAST_1)
            .build();

        S3VectorsRxJavaExample example = new S3VectorsRxJavaExample(client);

        List<PutInputVector> toInsert = List.of(
            PutInputVector.builder()
                .key("vec1")
                .data(d -> d.float32(List.of(/* floats matching dimensions */)))
                .metadata(Map.of("category","A","timestamp","168"))
                .build()
        );

        example.putVectors(toInsert)
            .doOnSuccess(v -> System.out.println("Inserted vectors"))
            .flatMap(v -> example.queryNearest(
                List.of(/* query floats matching dimensions */),
                10,
                Map.of("category","A"),
                true,
                true))
            .subscribe(results -> {
                results.forEach(r -> {
                    System.out.println("Key: " + r.key());
                    System.out.println("Distance: " + r.distance());
                    System.out.println("Metadata: " + r.metadata());
                });
            }, Throwable::printStackTrace);
    }
}
```
