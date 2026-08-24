## ADDED Requirements

### Requirement: Batch Embedding API
`UnifiedEmbeddingService` SHALL provide a `generateEmbeddings(List<String>)` method that sends multiple texts in a single `/embeddings` API call.
The batch method SHALL return a `List<float[]>` where `result[i]` corresponds to `input[i]`.
The method SHALL reuse the existing `TokenBucketRateLimiter` for rate limiting.
The method SHALL support up to 20 texts per batch (configurable via `embedding.batch-size`).

#### Scenario: Batch embedding returns vectors in input order
- GIVEN a list of 3 texts ["方法A", "方法B", "方法C"]
- WHEN `generateEmbeddings` is called
- THEN the response SHALL contain 3 float arrays in the same order
- AND each array SHALL have dimension 2048

#### Scenario: Empty input list
- GIVEN an empty or null input list
- WHEN `generateEmbeddings` is called
- THEN the method SHALL return an empty list or throw `IllegalArgumentException`

#### Scenario: Single text falls back to existing path
- GIVEN a list with a single text
- WHEN `generateEmbeddings` is called
- THEN the method MAY delegate to the existing `generateEmbedding(String)` or process as batch

### Requirement: Backward Compatibility
Existing callers of `generateEmbedding(String)` SHALL NOT be affected.
The `EmbeddingService` facade SHALL delegate `batchGenerateEmbeddings(List<String>)` to `UnifiedEmbeddingService.generateEmbeddings`.

#### Scenario: Existing single-call path unchanged
- GIVEN the existing `VectorWriter.upsertMethod` calling `embeddingService.generateEmbedding(text)`
- WHEN the batch API is deployed
- THEN the single-call path SHALL continue to work without modification
