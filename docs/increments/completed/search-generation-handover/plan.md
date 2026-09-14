# Search generation handover plan

- [x] Keep `searchable_generation` on publication and withdraw it only when the served generation itself is marked pending or failed.
- [x] Read the served generation in `isCurrent` and identity-scoped `currentGenerations`.
- [x] Add retained generations to `DocumentChunkPort`; use them in the stale sweep and a per-document `purgeObsolete` after readiness.
- [x] Target the served generation for Source access refreshes and its index metadata.
- [x] Cover the handover in `SearchIndexWorkIntegrationTest` and both cleanup paths in `OpenSearchRetrievalIntegrationTest`.
- [x] Update Document, Search and Connector specs and verification matrices.
- [x] Core `io.memoryos.ingestion/retrieval/document/connector` tests locally (247 cases; the first run caught `Set.of` rejecting an identical served/current generation, fixed and rerun green), API/Worker test compilation.
- [x] `clean check` in CI (PR #146 run 34849662520 and main run 34850839912 green); deployed to staging by run 34852084319.
- [x] Staging: reindex a ready Document and confirm it stays searchable until the switch.

## Staging evidence — 2026-09-14

`OrgMemory_POC_Guide.docx` (138 chunks) was reindexed through `POST /api/sources/{sourceId}/items/{itemId}/index-attempts` (202) at 13:58:00 UTC. Sampling PostgreSQL and `POST /api/search` about every 2.5 seconds:

| Time (UTC) | content generation | served generation | Search hit generation |
| --- | --- | --- | --- |
| 13:58:01–13:58:09 | `c60c6229` | `c60c6229` | `c60c6229` |
| 13:58:11–13:58:14 | `75852b81` (published) | `c60c6229` | `c60c6229` |
| 13:58:17 | `75852b81` | `75852b81` | `75852b81` |

All 7 samples returned the Document (0 missing), and `search_error_code` stayed empty. The worker logged no `search.index.purge_failed` event. The replaced generation's chunk count was not read directly from OpenSearch; per-document cleanup is covered by `OpenSearchRetrievalIntegrationTest`.
