# 0008 — OpenSearch projection and normalized hybrid Search

Date: 2026-09-08. Status: Accepted; implementation in MEM-46.

## Decision

Use Spring AI `EmbeddingModel` for both passage and query embeddings, and the native OpenSearch Java client for precomputed-vector writes and hybrid queries. The initial model is OpenAI `text-embedding-3-large`, 3072 dimensions. Within one query, OpenSearch combines title/content BM25 and content-vector k-NN using `normalization-processor`, `min_max`, `arithmetic_mean`, and default weights 0.5/0.5. This is the accepted reference implementation-aligned path; no alternative fusion experiment or separate Lucene index is introduced.

PostgreSQL owns the current Document generation, bounded current chunks with source provenance, and durable indexing work. Vector arrays are persisted only in OpenSearch. MinIO retains the original source object and canonical extraction JSON. An OpenSearch repository snapshot, when an operator configures one, is a separate recovery resource; it is not a PostgreSQL embedding artifact ledger.

The extraction transaction publishes `DocumentChanged` synchronously; Ingestion records `search_index_operations` in that transaction. The existing PostgreSQL dispatch, Redis identifier delivery, worker claim, renewal and acknowledgment path gains a `SEARCH` workload. Extraction completion and Search readiness remain distinct.

The physical index identity includes embedding endpoint, model, dimensions and chunk convention. Chunk IDs include tenant, document, generation and ordinal. Each bulk item must succeed, and the generation's indexed chunk count must match before PostgreSQL records Search readiness. Query results are checked against current ready generations and existing source eligibility before document grouping. Late writes to obsolete generations cannot overwrite current generation IDs and are removed by a recurring sweep.

## Consequences

- Search has one backend for keyword and semantic retrieval. Spring AI does not perform a second embedding call through `VectorStore.add()`.
- Current chunks and canonical JSON can rebuild an index. Reusing surviving vectors for unchanged input avoids embedding calls; loss of index and snapshots requires paid re-embedding. PostgreSQL backups alone do not restore vectors.
- There is no cross-store atomic transaction and no exactly-once embedding guarantee. Claims, generation IDs, per-item checks and recurring reconciliation provide eventual recovery. Search readiness is cleared before repair writes.
- Changing the model space selects another physical index and rebuilding gradually makes current generations ready there. This increment does not claim zero-downtime model migration. Old physical indices require an operator retention decision after verification.
- Provider model aliases are not immutable revisions. A provider-side change under the same alias requires an explicit index-prefix/profile change and regeneration; local identity cannot detect an unannounced provider change.
- Detailed source permission expansion remains deferred. Existing tenant/session/source eligibility stays in force.
- Chat is MEM-11. Multi-query planning, weighted RRF across query lists, context expansion, answer generation, conversation storage and streaming are implemented with that consumer later.

## Verification boundary

Repository tests exercise real PostgreSQL and OpenSearch, provider-response validation, worker fencing and browser contracts. Synthetic provider vectors verify wiring and ranking mechanics, not OpenAI semantic quality. Live OpenAI quality, deployment snapshot restore, corpus capacity and end-to-end deployment acceptance remain tracked in the active increment.

## 2026-09-09 addendum — semantic admission before normalization

The accepted hybrid path now uses radial k-NN with a configurable raw semantic score floor instead of admitting a fixed top-K semantic list. The default OpenSearch score floor is `0.70` for the current Faiss cosine mapping, equivalent to cosine similarity `0.40`; the existing candidate limit becomes the `ef_search` and hybrid response/pagination budget. BM25 remains an independent clause. This prevents weak nearest neighbors from becoming apparently relevant through query-relative min-max normalization without eliminating lexical-only or sufficiently strong semantic results. The value is an operational default calibrated against the local MEM-46 acceptance corpus for the selected model and must be reevaluated against representative judgments when the model or corpus changes.
