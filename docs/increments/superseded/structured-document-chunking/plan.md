# Structured chunking implementation plan

> Superseded planning draft: chunking, embedding and retrieval are delivered by [MEM-46 Search](../../completed/mem-46-search/plan.md); [MEM-11 Chat](../../completed/mem-11-production-chat/plan.md) has a separate completion gate. This checklist is historical, not a separate MEM-62 implementation plan.

- [ ] Freeze canonical extraction input and processing identity with extraction/native adapter work.
- [ ] Review OrgMemory block-aware chunker implementation and tests at a recorded SHA; identify portable algorithms without copying unrelated layers.
- [ ] Define named policy, tokenizer, token bounds, heading context, table header repetition and oversized-cell behavior.
- [ ] Add PostgreSQL chunk-set/chunk persistence and migrations, stable identities and immutable manifests.
- [ ] Implement real ingestion orchestration with version/claim fencing, cancellation, idempotency and cleanup.
- [ ] Add capability API for MEM-48; leave embeddings and vector storage absent here.
- [ ] Prove golden fixtures for Vietnamese prose, multi-tab Sheets, repeated/merged headers, long tables and oversized input.
- [ ] Prove policy change, duplicate delivery, restart, stale publication, delete and revoked-source behavior.
- [ ] Expose truthful processing status through existing API/UI; no separate configuration wizard.
- [ ] Run narrow tests, `gradlew clean check`, frontend gates if changed and staging document-to-chunk evidence.
- [ ] Update Document/Ingestion spec/test pairs and architecture in the substantive change.

Completion: persisted chunks can be consumed by MEM-48; this is not embedding completion or search availability.
