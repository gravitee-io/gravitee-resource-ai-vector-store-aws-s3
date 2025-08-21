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
      "readOnly": false,
      "allowEviction": true,
      "evictTime": 1,
      "evictTimeUnit": "HOURS"
    },
    "awsS3VectorsConfiguration": {
      "vectorBucketName": "my-vector-bucket",
      "vectorIndexName": "default-index",
      "encryption": "SSE_S3",
      "kmsKeyId": null,
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

| Field            | Description                                                                            |
|------------------|----------------------------------------------------------------------------------------|
| `embeddingSize`  | Size of the input embedding vector. Must match the size used by your model.            |
| `maxResults`     | Number of top similar vectors to return per query.                                     |
| `similarity`     | Similarity function: `COSINE` or `EUCLIDEAN`.                                          |
| `threshold`      | Minimum similarity score to return results.                                            |
| `readOnly`       | If `true`, disables writes and only performs queries.                                  |
| `allowEviction`  | If `true`, sets an `expireAt` metadata field on vectors. Eviction is consumer-managed. |
| `evictTime`      | Time after which vectors are considered expired (for tagging only).                    |
| `evictTimeUnit`  | Time unit for eviction: `MINUTES`, `HOURS`, or `DAYS`.                                |

---

### AWS S3 Vectors Configuration

| Field                   | Description                                              |
|-------------------------|----------------------------------------------------------|
| `vectorBucketName`      | S3 vector bucket name (3–63 lowercase, numbers, hyphens) |
| `vectorIndexName`       | Index name within the bucket; immutable after creation   |
| `encryption`            | `"SSE_S3"`, `"SSE_KMS"`, or `"NONE"`                   |
| `kmsKeyId`              | ARN of the KMS key if using `"SSE_KMS"`                 |
| `region`                | AWS region where the bucket/index exist                  |
| `awsAccessKeyId`        | AWS access key ID                                        |
| `awsSecretAccessKey`    | AWS secret access key                                    |
| `sessionToken`          | AWS session token (optional)                             |

---

## 🧑‍💼 Multi-Tenant Isolation via Metadata

To enable multi-tenant isolation, include a unique context key (`retrieval_context_key`) in the metadata of each vector when adding. Queries can be filtered using this metadata key. Deletions are not filtered by this key in the resource implementation.

---

## 🧠 How It Works

1. **Initialization:** On startup, the resource ensures the S3 bucket and index exist, creating them if necessary. The index is created with the dimension and distance metric specified in `properties`.
2. **Adding Vectors:** Vectors are added one at a time. If `allowEviction` is enabled, an `expireAt` metadata field is set. If `retrieval_context_key` is present in metadata, it is stored for isolation.
3. **Querying Vectors:** Queries can filter by `retrieval_context_key` in metadata. Results include similarity/distance scores and metadata.
4. **Removing Vectors:** Vectors are removed by key. No metadata filtering is applied to deletions.
5. **Eviction:** The resource only tags vectors with `expireAt`; consumers must implement their own cleanup logic to remove expired vectors.

---

## 🧠 Example Usage Pattern

1. **Create a vector bucket and index** via AWS Console, CLI, SDK, or let the resource create them. Set dimensions and distance metric via configuration.
2. **Insert vectors** one at a time using the resource API. Each vector has a unique key, float32 embedding matching `embeddingSize`, and metadata. Include `retrieval_context_key` in metadata for multi-tenant isolation if desired.
3. **Query vectors** using the resource API, optionally filtering on `retrieval_context_key` in metadata, and returning similarity/distance scores and metadata.
4. **Delete vectors** using the resource API by key. No metadata filtering is applied to deletions.
5. **Eviction** (if enabled): Vectors are tagged with `expireAt` in metadata. Consumers must periodically remove expired vectors.

---

## ✅ Features
- Fully managed, no infrastructure to run or patch
- Scales to millions/billions of vectors and thousands of indexes
- Low-cost, up to 90% cheaper than dedicated vector DBs
- Strong read-after-write consistency (provided by AWS S3 Vectors)
- Fine-grained IAM-based access control
- Seamless integration with Bedrock Knowledge Bases and OpenSearch
- Multi-tenant isolation via metadata keys

---

## ⚠️ Limitations
- No automatic eviction: expired vectors are only tagged; consumers must remove them
- Only single vector operations are supported (no batch insert)
- Deletions are by key only; no metadata filtering
- Index distance metric is set via `similarity` property

---

## 🗃 Supported Similarity Functions

- `COSINE`: Ideal for normalized embeddings.
- `EUCLIDEAN`: Computes L2 distance.
> **Note:** The index’s distance metric is set from the `similarity` property and must match your embeddings.

---

## 🚀 Use Cases

- Retrieval-Augmented Generation (RAG) with Amazon Bedrock
- Semantic search over large document/media sets
- Agent memory/context vector storage
- Multi-tenant indexing (via metadata keys)

---

## 📌 Summary

This **S3‑Tensors (Amazon S3 Vectors)** resource delivers a fully managed, scalable, cost-efficient vector store on S3. It is ideal for production-grade RAG and semantic search pipelines, with strong AWS-native integration, and multi-tenant isolation.

---

## 🚀 Example SDK Usage (Java + RxJava + AWS SDK v2)

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

    public Maybe<Void> putVector(PutInputVector vector) {
        CompletableFuture<Void> fut = client
            .putVectors(PutVectorsRequest.builder()
                .vectorBucketName(vectorBucketName)
                .indexName(vectorIndexName)
                .vectors(vector)
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

        PutInputVector toInsert = PutInputVector.builder()
            .key("vec1")
            .data(d -> d.float32(List.of(/* floats matching dimensions */)))
            .metadata(Map.of("retrieval_context_key","tenant-xyz","timestamp","168"))
            .build();

        example.putVector(toInsert)
            .doOnSuccess(v -> System.out.println("Inserted vector"))
            .flatMap(v -> example.queryNearest(
                List.of(/* query floats matching dimensions */),
                10,
                Map.of("retrieval_context_key","tenant-xyz"),
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
