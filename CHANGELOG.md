# 1.0.0-alpha.1 (2025-09-10)


### Bug Fixes

* allow findrelevant actions in readonly mode ([1bcf0d7](https://github.com/gravitee-io/gravitee-resource-ai-vector-store-aws-s3/commit/1bcf0d70ea3bebc62a1c57b07d7953b9628a4404))
* include required datatype (float32) during index creation ([d86da0f](https://github.com/gravitee-io/gravitee-resource-ai-vector-store-aws-s3/commit/d86da0f7e0ec0f69e04d7388540794831d808b5c))
* include text from prompt in metadata when persisting vectors ([763ff15](https://github.com/gravitee-io/gravitee-resource-ai-vector-store-aws-s3/commit/763ff15ff6ccb74f1d0874c47fa536a1b9337504))
* tolowercase similarity during index creation ([7aacd9a](https://github.com/gravitee-io/gravitee-resource-ai-vector-store-aws-s3/commit/7aacd9ab95d0fbb18509dbbf80363f2144d6b94a))


### Features

* include ability to expire and evict vectors after some user-specified period of time ([dc80dd6](https://github.com/gravitee-io/gravitee-resource-ai-vector-store-aws-s3/commit/dc80dd6fa70fa627f367dd53e787895bb2e8c017))
* include retrieval_context_key handling if included in vectorentity metadata ([ebcdab1](https://github.com/gravitee-io/gravitee-resource-ai-vector-store-aws-s3/commit/ebcdab1d936d139f41762b64b48db3ad5d5a8ab5))
* minimize implementation to only stamp expireat metadata property ([a46cdcb](https://github.com/gravitee-io/gravitee-resource-ai-vector-store-aws-s3/commit/a46cdcb3946a8b8a5b6577073dca84fac72fb78a))
* queryable, modifiable vector store resource backed by aws s3 vectors ([21c785a](https://github.com/gravitee-io/gravitee-resource-ai-vector-store-aws-s3/commit/21c785a90dc57c19bb5d0a0f37315d5f9c40dbfc))
* support resourcecontextkey for segregation / multi-tenancy ([61aa208](https://github.com/gravitee-io/gravitee-resource-ai-vector-store-aws-s3/commit/61aa208583410861680a68211d32287be9621353))
* support sse-kms encryption (optionally) during s3 bucket creation ([17e5e78](https://github.com/gravitee-io/gravitee-resource-ai-vector-store-aws-s3/commit/17e5e786b51e6209b629e6466d6b2c96c5f428be))
