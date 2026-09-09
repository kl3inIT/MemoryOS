# Search verification matrix

| Contract | Evidence |
| --- | --- |
| Bounded checksum-verified artifact reads release object/reader resources on rejection; Vietnamese headings/page provenance; merged/repeated table headers, numeric values, row provenance, wide/oversized table bounds and bounded Unicode | `DocumentChunkServiceTest`, `StructuredDocumentChunkerTest` |
| Response count/order/model/dimension/finite values, defensive copies and safe provider failures | `ValidatedEmbeddingServiceTest` |
| Null/invalid media filters produce validation errors; immutable request filters | `SearchRequestTest` |
| Embedding credentials require HTTPS; transport/malformed provider JSON is sanitized without masking programming failures | `SearchPropertiesTest`, `OpenSearchGatewayTest` |
| Actual Spring AI OpenAI HTTP request, explicit model/dimensions/FLOAT input, response decoding and 401 handling | `SpringAiEmbeddingHttpTest`; local HTTP provider fixture |
| Native 3072-dimensional Faiss mapping, normalized hybrid query, Tenant/MIME filtering, vector reuse, old-generation cleanup, delete and full index loss/rebuild | `OpenSearchRetrievalIntegrationTest`; real pinned OpenSearch 3.8.0 |
| Alias inspection remains restricted to its actual target indexes and rejects an alias spanning multiple physical indexes | `OpenSearchRetrievalIntegrationTest`; secured staging checks recorded in MEM-46 |
| Transactional publication/outbox, bounded chunks, durable retry, duplicate deliveries, stale completion and DELETE surviving Document deletion | `SearchIndexWorkIntegrationTest`; real PostgreSQL/Flyway |
| JdbcClient multi-row publication across 259 chunks, Unicode/quoted text round-trip, and rollback of replacement when a later INSERT fails | `SearchIndexWorkIntegrationTest.publishesMultipleChunkBatchesAndRollsBackReplacementWhenALaterBatchFails`; real PostgreSQL |
| Closed capabilities/no dependency cycles | `ModulithArchitectureTest`, `CoreDependencyRulesTest` |
| Redis SEARCH topology, bounded workload labels and control-plane startup | `RedisExecutionTopologyIntegrationTest`, `ControlPlaneIntegrationTest`, `RedisOperationRelayTest` |
| Existing FILE extraction/removal contract | `SourceApiIntegrationTest`, `WorkerFileProcessingIntegrationTest` |
| Live Spring API schema and generated client stability | `OpenApiContractTest`, `pnpm check:api` |
| Search response fields are required in the live schema | `OpenApiContractTest` |
| Trusted Keycloak origin/no credential redirects; leaf renewal preserves authority/credentials and rolls back on reload failure | `infrastructure/opensearch/test_security_operations.py`; generated real test certificates, stubbed container reload |
| Global-tenant index pattern/saved-search provisioning uses the supported API, hides the internal credential, avoids replay writes, reconciles drift and preserves inspector read-only roles | `infrastructure/opensearch/test_security_operations.py`; mocked Dashboards transport plus checked-in Security policy |
| Adjacent-hit sections in document order; best-hit score/anchor; gaps; duplicate ordinals; stale generation/source exclusion; deterministic ties; limit three sections after merging; per-chunk provenance without neighbor reads | `DocumentSearchServiceTest` |
| Dedicated centered Search landing and reduced-motion-aware dock transition; capability-gated Search-only `+` menu for FILE upload/source browsing with keyboard/Escape/focus return and no Chat action; browser-supported voice transcript without auto-submit/audio upload plus unsupported/permission feedback; post-submit Radix time/file-type menus, result workspace/file-type facet, bounded snippets/literal highlights/friendly metadata, paging/cancel/retry/empty/out-of-order requests, responsive dialog, section switching, Escape close and focus return, with extracted markup retained as text | `web/src/features/search/search-page.test.tsx`, `web/src/features/search/search-presentation.test.ts`, `web/tests/e2e/search.spec.ts`; component tests plus browser HTTP fixtures |
| Existing identity/navigation/Source browser behavior with Search home | `identity-shell.spec.ts`, Search page unit tests |

The following acceptance evidence remains distinct: approved OpenAI embeddings over representative Vietnamese/identifier/table corpora; provider cost and latency/concurrency; complete deployed FILE→MinIO→worker→OpenSearch→API→browser; native Google input where its provider exists; configured snapshot restore/catch-up and production RPO/RTO. Test fixtures do not stand in for those measurements. Current runs and blockers are recorded in the [active verification log](../increments/active/mem-46-search/verification.md).
