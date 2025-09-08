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
      "embeddingSize": 384,
      "maxResults": 5,
      "similarity": "COSINE",
      "threshold": 0.9,
      "readOnly": true,
      "allowEviction": false,
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

| Field            | Description                                                                                                                                                                                                                 | Required | Default   |
|------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|-----------|
| `embeddingSize`  | Dimension of the embedding vectors. This must match the dimensionality used by the model generating embeddings.                                                                                                              | Yes      | 384       |
| `maxResults`     | Maximum number of results returned in a vector search query.                                                                                                                                                                | Yes      | 5         |
| `similarity`     | The distance metric used to compare embedding vectors. `COSINE` measures the cosine of the angle between vectors. Best for normalized vectors and when direction matters more than magnitude. `EUCLIDEAN` measures the straight-line distance between vectors. Best when both direction and magnitude are important. | Yes      | COSINE    |
| `threshold`      | Minimum similarity score for a result to be considered relevant. Set this value higher to filter out less relevant results.                                                                                  | Yes      | 0.9       |
| `readOnly`       | If true, disables writes and enables read-only access to the index.                                                                                                                 | Yes      | true      |
| `allowEviction`  | Enable automatic eviction of old or unused entries from the vector store when `readOnly` is false. Only shown if `readOnly` is false.                                               | No       | false     |
| `evictTime`      | Duration after which an unused vector entry can be evicted. Only shown if `allowEviction` is true.                                                                                  | No       | 1         |
| `evictTimeUnit`  | Unit of time used to define eviction duration. Defines how long unused vectors are retained. Only shown if `allowEviction` is true.                                                        | No       | HOURS     |

---

### AWS S3 Vectors Configuration

| Field                   | Description                                                        | Required | Default   |
|-------------------------|--------------------------------------------------------------------|----------|-----------|
| `vectorBucketName`      | S3 vector bucket name (3–63 lowercase, numbers, hyphens).          | Yes      |           |
| `vectorIndexName`       | Index name within the bucket; immutable after creation.             | Yes      |           |
| `encryption`            | S3 server-side encryption type. (`SSE_S3`, `SSE_KMS`, or `NONE`)   | Yes      | SSE_S3    |
| `kmsKeyId`              | ARN of the KMS key if using `SSE_KMS`.                            | No       | null      |
| `region`                | AWS region where the bucket/index exist.                           | Yes      |           |
| `awsAccessKeyId`        | AWS access key ID for authentication.                              | Yes      |           |
| `awsSecretAccessKey`    | AWS secret access key for authentication.                          | Yes      |           |
| `sessionToken`          | AWS session token (optional).                                      | No       | null      |

---

#### Required Fields

- **Top-Level Properties:** `embeddingSize`, `maxResults`, `similarity`, `threshold`, `readOnly`
- **AWS S3 Vectors Configuration:** `vectorBucketName`, `vectorIndexName`, `encryption`, `region`, `awsAccessKeyId`, `awsSecretAccessKey`

#### Conditional Display

- `allowEviction` is only shown if `readOnly` is false.
- `evictTime` and `evictTimeUnit` are only shown if `allowEviction` is true.

---

## 🧑‍💼 Multi-Tenant Isolation via Metadata

To enable multi-tenant isolation, include a unique context key (`retrieval_context_key`) in the metadata of each vector when adding. Queries can be filtered using this metadata key. Deletions are not filtered by this key in the resource implementation.

## 🧩 Metadata Codec: Why & How

The resource uses a custom metadata codec (`S3VectorsMetadataCodec`) to safely encode and decode metadata for AWS S3 Vectors. This codec:
- Converts Java metadata (Map<String, Object>) into the AWS S3 Vectors Document format for storage and filtering.
- Ensures only supported primitive types (string, number, boolean, lists of these) are filterable in S3 Vectors.
- Packs complex or nested metadata into a non-filterable blob field, allowing round-trip retrieval without loss.
- Decodes metadata from S3 Vectors back into Java objects for use in your application.

**Usage in the project:**
- When adding or querying vectors, metadata is encoded using the codec to ensure compatibility and filtering support.
- When retrieving results, metadata is decoded back to its original structure, including any complex/nested fields.

This approach ensures robust, lossless metadata handling and supports advanced use cases like multi-tenant isolation and custom metadata fields.

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
