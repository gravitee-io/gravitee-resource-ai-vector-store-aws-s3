# Gravitee Resource Redis AI Vector Store

This resource provides vector search capabilities using **Redis** as the underlying vector store. It is designed to be integrated into AI pipelines that rely on semantic similarity, retrieval-augmented generation (RAG), or embedding-based search.

Supports advanced features like **HNSW indexing**, **cosine similarity**, and configurable eviction.

---

## 🔧 Configuration

To use this resource, register it with the following configuration:

```json
{
  "name": "vector-store-redis-resource",
  "type": "ai-vector-store-redis",
  "enabled": true,
  "configuration": {
    "properties": {
      "embeddingSize": 384,
      "maxResults": 5,
      "similarity": "COSINE",
      "threshold": 0.3,
      "indexType": "HNSW",
      "readOnly": false,
      "allowEviction": true,
      "evictTime": 10,
      "evictTimeUnit": "SECONDS"
    },
    "redisConfig": {
      "url": "redis://localhost:62848",
      "username": "default",
      "password": "defaultpass",
      "index": "redis_index",
      "prefix": "redis_prefix",
      "query": "@retrieval_context_key:{\n\t$retrieval_context_key\n}=>[\n\tKNN $max_results @vector $vector AS similarity_score\n]",
      "scoreField": "similarity_score",
      "vectorStoreConfig": {
        "initialCapacity": 5,
        "blockSize": 10
      }
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
| `similarity`     | Similarity function: `COSINE`, `EUCLIDEAN`, or `DOT_PRODUCT`.               |
| `threshold`      | Minimum similarity score to return results.                                 |
| `indexType`      | Type of vector index. Supports `FLAT` or `HNSW`.                            |
| `readOnly`       | If `true`, disables writes and only performs queries.                       |
| `allowEviction`  | Enables automatic eviction of stale vectors.                                |
| `evictTime`      | Time after which vectors can be evicted.                                    |
| `evictTimeUnit`  | Time unit for eviction: `SECONDS`, `MINUTES`, etc.                          |

---

### Redis Configuration

| Field            | Description                                                                 |
|------------------|-----------------------------------------------------------------------------|
| `url`            | Redis connection URL.                                                       |
| `username`       | Redis username (if using ACL).                                              |
| `password`       | Redis password.                                                             |
| `index`          | Name of the Redis search index.                                             |
| `prefix`         | Prefix for Redis keys used by this store.                                   |
| `query`          | RediSearch query template with support for metadata and vector placeholders.|
| `scoreField`     | Field name in results where similarity score is stored.                     |
| `vectorStoreConfig.initialCapacity` | Minimum initial vector capacity.                         |
| `vectorStoreConfig.blockSize`       | Allocation block size for vector storage.               |

---

## 🧠 Example Query Template

```text
@retrieval_context_key:{
  $retrieval_context_key
}=>[
  KNN $max_results @vector $vector AS similarity_score
]
```

Use `$vector`, `$max_results`, and custom metadata variables (e.g. `$retrieval_context_key`) as placeholders. These will be replaced dynamically at query time.

---

## ✅ Features

- Fast vector search with HNSW support via RediSearch.
- Optional eviction policy for controlling memory footprint.
- Score thresholding to filter low-relevance results.
- Customizable query templates and Redis key structures.
- Compatibility with most embedding models (OpenAI, MiniLM, etc.).

---

## 🗃 Supported Similarity Functions

- `COSINE`: Ideal for normalized embeddings.
- `EUCLIDEAN`: Computes L2 distance.
- `DOT_PRODUCT`: Suitable when vector magnitude matters.

---

## 🚀 Use Cases

- RAG (Retrieval-Augmented Generation)
- Semantic Search
- Chat history/context vector lookup
- Multi-tenant vector indexing with key prefixes

