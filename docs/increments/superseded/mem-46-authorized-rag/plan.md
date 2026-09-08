# MEM-46 — Integrated execution plan

> Superseded combined planning draft — 2026-09-07: the user split delivery into [MEM-46 Search](../../active/mem-46-search/design.md) and [MEM-11 production Chat](../../active/mem-11-production-chat/design.md). Search has its own completion gate and is implemented first. The narrative below records earlier combined research; statements requiring both modes to close MEM-46 are no longer execution instructions. Read [Onyx Search details](../../active/mem-46-search/onyx-search.md) for the verified UI/SearchTool/API distinction.

Status: direction accepted; implementation has not started in this change. [Design](design.md) owns OpenSearch-only vector placement and the remaining capacity/model/version measurements. [MEM-46](https://linear.app/memory-os/issue/MEM-46) owns the complete delivery, including the former MEM-62/47/48/49/50/71/72/11 scope. [Implementation map](implementation-map.md) gives folders, dependencies and first runtime outcomes.

The steps below are internal implementation order in one increment. No step is a separately accepted partial product; completion requires the full source-to-answer flow.

Latest user sequencing: settle the [functional flow](search-chat-core-flow.md) first; defer the permission-specific design discussion. Read the existing permission checklists as later integration work, not prerequisites to explaining/selecting the embedding, hybrid and Chat algorithms now. Compare recipe-style rank fusion with OpenSearch native normalized-score fusion before selecting one within-query runtime path; Chat multi-query weighted RRF remains a separate step.

The [illustrated flow and Onyx comparison](flow.md) maps these steps to data objects, model calls, authorization gates and failure/recovery paths.

The [Search-first / production Chat contract](search-and-production-chat.md) adds the user's two-mode requirement. Search is the first real browser/runtime outcome; Chat is completed in this same increment. OpenSearch is the sole durable vector store; PostgreSQL stores no vector arrays.

## 1. Freeze contracts and measured choices

- [ ] Read current Document/Connector/Ingestion/IAM specs and verification matrices; preserve current-Document semantics and the existing durable-operation architecture.
- [ ] Validate Spring AI BOM 2.0.1 with MemoryOS Boot 4.1.1, Gradle and OTLP dependency train; select only consumed model/client modules and a compatible native OpenSearch client.
- [ ] Confirm actual gateway protocol, embedding/chat routes and model revisions from MEM-66/MEM-67. Measure Vietnamese query/document instructions, tokenizer, dimension, normalization, context limits, structured-output support, CPU batch size, timeouts and concurrency.
- [ ] Apply the [recipe review](spring-ai-recipes-review.md): compose role-specific query/selection/answer clients with shared approved transport/observations but explicit advisor scope; prove internal calls do not recursively retrieve or inherit unrelated memory/tools. Schema validation and SDK retries share the operation budget.
- [ ] Measure the selected OpenSearch-only vector path: chunk count/dimension, index/replica/snapshot bytes, CPU regeneration cost and recovery time; select production topology and RPO/RTO before deployment.
- [ ] Implement one vector storage path with OpenSearch snapshots, index-side stale-write fencing and restore/regeneration acceptance. PostgreSQL migrations add chunk/identity/operation/chat data, not vector columns or a separate embedding-artifact ledger.
- [ ] Define artifact-read/reader protection, current chunk/embedding identities, projection eligibility, citation staleness and permitted history under source revocation. Keep one source of truth for each contract.
- [ ] Set shared evaluation corpus and acceptance thresholds before tuning retrieval weights, context budgets or model options. Agree runtime/recovery and latency/cost budgets from evidence.

## 2. Structured artifact to current chunks

- [ ] Implement the real provider-neutral artifact read through Document lifecycle, checksum validation and reader protection; preserve existing extraction completion behavior.
- [ ] Implement heading-aware prose, row/header-aware tables and bounded oversized splitting from typed canonical blocks. Preserve typed values and actual source locators separately from embedding text.
- [ ] Define merged/repeated headers, wide tables, oversized cells, overlaps and token bounds using the chosen model tokenizer. Review OrgMemory golden tests before porting only applicable algorithms.
- [ ] Add concrete PostgreSQL chunk persistence/migrations and transactional current-set adoption. Fence claims, artifact/hash, policy/tokenizer identity and generation; safely discard/clean obsolete output.
- [ ] Exercise retry, restart, cancellation, stale publication, replace/delete/revoke and reader/cleanup races through the existing worker. ACL-only changes must not trigger rechunking.

## 3. Spring AI embedding and bounded batches

- [ ] Compose the approved `EmbeddingModel` in worker and API consumers with explicit routes; use exact approved text and response-bearing batches.
- [ ] Validate count, response indices, expected dimension, finite values, normalization and model identity. Test empty, partial, reordered, mixed-dimension and malformed responses.
- [ ] Commit durable indexing intent before external IO. Validate actual vectors in bounded memory/private temporary staging, keep exact input/model/current-chunk identity and write vector arrays only into OpenSearch; no long transaction around model calls.
- [ ] Coordinate SDK retry/timeout with durable worker retry and cancellation. Recheck eligibility before egress/write/completion; prove idempotent records and stale-result rejection without claiming exactly-once embedding calls.
- [ ] Record synthetic model requests to prove payload boundaries. Reuse unchanged vectors from a readable compatible index/snapshot; regenerate missing output after loss and explicitly re-embed changed model/input.

## 4. Real OpenSearch projection and recovery

- [ ] Provision explicit TLS/auth/Infisical connectivity and bounded requests; own versioned mappings/aliases with native BM25 analyzers, vector dimension and complete Tenant/source/ACL/generation provenance.
- [ ] Bulk-write validated precomputed batches through the native client. Prove this path makes no second embedding call; do not use `OpenSearchVectorStore.add()` for precomputed-vector projection.
- [ ] Add transactionally durable indexing intent, identifier-only Redis dispatch and index-side/generation fencing. Verify per-item success/searchable identity before PostgreSQL completion; reconcile uncertain successful writes after crash and re-embed only when no reusable verified output survives.
- [ ] Publish truthful extraction/chunk/embedding/search readiness. Make replacement/revoke/delete ineligible before asynchronous cleanup; stale workers and rebuilds cannot restore eligibility.
- [ ] Implement bounded reconciliation, readable-index reuse, snapshot restore with post-snapshot catch-up and full index/snapshot-loss regeneration; verify current authority before alias cutover and old-index cleanup. Configure protected S3-compatible snapshots and test independent-failure recovery, shard/replica/refresh/bulk/retention and RPO/RTO.

## 5. Authorized hybrid multi-query retrieval

Implement the shared direct-query primitive and the complete Search consumer below before wiring Chat multi-query. Multi-query remains required for Chat; Search's direct-query behavior is intentional and shares all permission/index mechanics.

- [ ] Resolve live Actor/Tenant/source-ACL scope before query construction; bind scope server-side and preserve it across fan-out.
- [ ] Use `ChatClient` structured output for semantic/keyword/subqueries, retaining the original question. Validate schema and domain bounds, dedupe queries and cap model calls/fan-out/latency/cost.
- [ ] Test follow-up query rewriting from permitted history, including removed/revoked context and ambiguous pronouns. Preserve original-query fallback. HyDE is an unselected evaluation candidate, not a required runtime step or evidence source.
- [ ] Embed queries with the active index's compatible model identity and proper query instruction. Define explicit mismatch/model/search/policy outcomes.
- [ ] Execute native BM25/vector hybrid search per query with mandatory filters and bounded candidates/pagination; reauthorize selected evidence before returning any text or metadata to a model/consumer.
- [ ] Implement weighted RRF across query lists, separate from lexical/vector fusion. Define rank origin/k/weights/duplicate-query semantics and deterministic ties with stable current-evidence identities.
- [ ] Test no-results, malformed query plans, branch failures, cancellation, stale projection and original-query fallback within one authorized backend and budget.

## 5a. Search production first

- [ ] Implement Search mode and actual server API over the shared authorized hybrid retrieval primitive; default Search invokes query embedding but no ChatModel query planning/answer generation.
- [ ] Return document-grouped results, escaped snippets/highlights, true source metadata/locators and permitted source/type/date filters. Define bounded stable ranking/pagination and authorized counts/facets, with current-generation/ACL changes handled explicitly.
- [ ] Implement source opening with live authorization, stale-artifact behavior, no-results, unavailable, loading and cancel/out-of-order-response handling. Mode changes never present stale private results under another identity/scope.
- [ ] Run FILE -> worker -> model -> OpenSearch -> real browser Search -> source-navigation acceptance and native-provider acceptance when its dependency is ready, including Allow/Deny/Revoke, index loss/recovery, relevance and latency. Do not close MEM-46 at this checkpoint.

## 6. Authorized selection and context expansion

- [ ] Merge adjacent/overlapping chunks within one current artifact/chunk set, retain headings/table headers/provenance and cap contribution per document.
- [ ] Use `ChatClient` structured selection over already-authorized candidates. Reject model-created evidence IDs and invalid ranges.
- [ ] Exercise valid JSON with out-of-allowlist/duplicate IDs, invalid ranges, empty selections and exhausted structured-output retries; schema validity alone never grants access or justifies unchecked list indexing.
- [ ] Fetch bounded neighbors through the same authorized retrieval boundary, rechecking current state before any LLM sees the text.
- [ ] Select extent from real source context, dedupe overlap, apply final token/byte/citation overhead budgets and revalidate final evidence. Never splice old/new artifacts.
- [ ] Define and test selection/expansion failures, empty context, timeout, retry, cancellation and safe allowed-passage fallback.

## 7. Grounded answer, citations and browser

- [ ] Integrate `ChatClient` answer generation through the actual capability/API/browser path, with explicit approved route and evidence-only prompt construction.
- [ ] Bind model citation IDs to server-owned current evidence and verify IDs; implement source navigation with live authorization and stale-artifact outcome.
- [ ] Implement loading, no-evidence/no-access, search/model unavailable, partial/canceled generation and citation states; document any streaming revocation limit honestly.
- [ ] Use only permitted conversation context; retrieved/source text cannot invoke tools, change authority or suppress citations. No implicit RAG advisor or tool path may fetch extra evidence.
- [ ] Integrate MEM-25 server-authored audit, safe model identity/outcomes/evidence IDs and existing OTLP observations; prompt/completion/retrieved-content capture stays disabled.
- [ ] Persist private conversation/message/generation attempts and citation bindings with ownership/retention/deletion contracts. Deduplicate repeated sends; explicit retry creates a linked attempt, reload reads durable state and abandoned runs converge after crashes.
- [ ] Exercise actual streaming through production-equivalent API/proxy with buffering, heartbeat, timeout, backpressure, cancellation/disconnect and bounded checkpoints; no duplicate completions or automatic replay of a partial answer.
- [ ] Reauthorize transcript reads and permitted model history; revoke/replaced evidence removes or redacts dependent assistant content under an explicit policy. Production content remains out of generic telemetry.
- [ ] Verify Search/Chat switching and Search-to-Chat handoff through shared evidence IDs and server reauthorization; both modes work in the completed delivery.

## 8. Integrated acceptance and consolidation

- [ ] Golden FILE and native Google fixtures prove typed-table/prose boundaries, true provenance, deterministic identities and model token caps.
- [ ] Real worker -> embedding -> OpenSearch -> query -> selection/expansion -> answer -> citation/browser smoke uses approved synthetic data; no mocked backend substitutes for runtime proof.
- [ ] Allow/Deny/Revoke/source ceiling/foreign Tenant/inactive membership/stale ACL and artifact changes are tested between every evidence/model/citation boundary. Recording models prove forbidden content and metadata never enter subsequent requests.
- [ ] PostgreSQL/Redis/OpenSearch integration proves duplicate/crash/stale claim/partial failure/rollback/delete/replace/cleanup/reconcile/index-loss/alias-cutover behavior.
- [ ] Shared Vietnamese evaluation compares BM25/vector/hybrid, single/multi-query/RRF and fixed/merged/selected/expanded context. Record Recall@k, MRR/nDCG, evidence precision, answer groundedness, citation support, p50/p95 latency, tokens/calls/cost and selected tradeoffs.
- [ ] Actual gateway smoke validates configured embedding/chat/structured output, timeouts and telemetry privacy. Native Google/source ACL and audit dependencies have their required integration evidence.
- [ ] Run applicable IDE static checks, checked-in Gradle `clean check`, relevant frontend/OpenAPI/browser gates and required CI/review. Record exact implementation SHA and staging evidence; planning is not implementation verification.
- [ ] Consolidate implemented facts in architecture, capability specs/test matrices and runbooks in the substantive code change. Record an ADR only when warranted after the choice is accepted and implementation starts.
- [ ] Close MEM-46 only when the whole chosen flow is delivered; retain intermediate lifecycle steps as completed internal checklist items, not new deferred issues. Move/reconcile increment lifecycle according to repository PR rules.
- [ ] Record production release and go-live evidence for both modes: exact SHA, real browser/model/index path, concurrency/latency/cost budgets, backups/restore drill, monitoring and rollback. Search is first in implementation order; production Chat is required before full completion.
