# Search generation handover plan

- [x] Keep `searchable_generation` on publication and withdraw it only when the served generation itself is marked pending or failed.
- [x] Read the served generation in `isCurrent` and identity-scoped `currentGenerations`.
- [x] Add retained generations to `DocumentChunkPort`; use them in the stale sweep and a per-document `purgeObsolete` after readiness.
- [x] Target the served generation for Source access refreshes and its index metadata.
- [x] Cover the handover in `SearchIndexWorkIntegrationTest` and both cleanup paths in `OpenSearchRetrievalIntegrationTest`.
- [x] Update Document, Search and Connector specs and verification matrices.
- [x] Core `io.memoryos.ingestion/retrieval/document/connector` tests locally (247 cases; the first run caught `Set.of` rejecting an identical served/current generation, fixed and rerun green), API/Worker test compilation.
- [ ] `clean check` in CI.
- [ ] Staging: reindex a ready Document and confirm it stays searchable until the switch.
