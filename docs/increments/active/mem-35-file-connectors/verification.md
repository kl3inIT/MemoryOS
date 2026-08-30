# MEM-35 verification matrix: Tenant-owned FILE connectors

This record defines required evidence before the increment can merge. Test names are assigned during implementation and must describe observable contracts rather than source structure.

| Requirement | Required evidence |
| --- | --- |
| Only an active Tenant OWNER can create, inspect, upload to, reindex, remove from, or delete a FILE source | Capability test through real transaction proxies plus HTTP integration with OWNER, MEMBER, inactive membership, and inactive Tenant |
| Connector creation creates/reuses exactly one Tenant NO_AUTH Credential and one PUBLIC ConnectorCredentialPair | PostgreSQL concurrent-create and uniqueness test |
| NO_AUTH contains no provider account, scopes, token, secret reference, or refresh behavior | Persistence assertion, API redaction assertion, and log capture |
| Pair identity is unique, composite FKs reject same-Tenant Connector/item mismatches, and cleanup target evidence is same-Tenant at creation | PostgreSQL migration, hostile inserts, and repository/API cleanup creation scenarios; MEM-24 later database-enforces one Tenant |
| UI/API use consumer-facing Source/SourceOperation contracts; sourceId maps internally to Pair and no persistence association/controller class leaks into paths, tags, or schemas | API Product Canvas review, OpenAPI snapshot, generated-client drift gate, and browser flow |
| Source detail exposes safe aggregate status, pending-work flag, access, last success, document count, and errors under overlapping attempts | Deterministic status-transition persistence test and browser polling flow |
| One upload accepts one file up to 10 MiB and rejects oversized input before durable partial state | Multipart HTTP integration plus PostgreSQL `octet_length`/size constraint assertion |
| SHA-256 is lowercase hexadecimal and corresponds to stored bytes | Application digest fixture plus PostgreSQL shape constraint |
| Actual detected media type—not extension—allows PDF, DOCX, UTF-8 TXT, and Markdown; allowed bytes with misleading/missing extension still succeed | Real parser fixtures covering valid, mismatch, unsupported, malformed, encrypted, and binary-text cases |
| ZIP recursion, OCR, macros, and embedded attachments are not processed | Parser configuration test with hostile/embedded fixtures |
| Extracted text is capped at 2,000,000 characters and timeout/write-limit failures are safe and terminal | Focused Tika integration with bounded output/time fixtures |
| Upload atomically persists ConnectorItemVersion BYTEA and one NOT_STARTED IndexAttempt without detection/parsing in the request | Capability transaction test and controlled extractor proving no invocation before commit |
| Unsupported/media/extraction outcomes appear asynchronously through safe Pair/attempt status, not fabricated upload HTTP errors | HTTP create response plus worker failure/status integration |
| Duplicate bytes in the one FILE Pair converge on one ConnectorItem/version and one Pair-specific attempt/mapping | Persistence and API idempotency integration; executable multi-Pair behavior is deferred to MEM-9/MEM-10 |
| NOT_STARTED attempts are skipped-locked across replicas and expired IN_PROGRESS leases are reclaimable | Digest-pinned PostgreSQL concurrency test |
| A reclaimed attempt may re-extract, but only the latest claim token can finalize | Controlled two-worker stale-token test |
| Worker crash/restart converges from expired lease without resetting terminal history | Real worker termination/restart integration |
| Worker performs extraction outside claim/finalization transactions | Transaction instrumentation proving no connection/row lock while the controlled extractor blocks |
| DocumentCommandService serializes same-content concurrent writes into one DocumentVersion; normal FILE finalization remains Pair-serialized | Direct PostgreSQL document-capability command concurrency test plus standard finalization integration |
| Finalization requires live claim, non-DELETING Pair/item, current version, and non-obsolete attempt | Conditional-update race tests against item removal, Pair deletion, and newer attempt |
| Pair-wide sequence makes overlapping and CANCELLED attempts deterministic; one late older success cannot hide pending work or overwrite a newer failure | PostgreSQL ordered attempt transitions including cancelled-only/no-mapping and late-completion state |
| Item reindex targets one selected Pair/current item version, reuses concurrent nonterminal work, and allocates Pair/item sequences without obsoleting unrelated items | API idempotency plus PostgreSQL concurrent reindex test |
| Failed extraction creates no partial searchable Document/version, mapping, or incorrect Pair count | Typed failure scenarios for every supported parser failure class |
| SourceDocumentAccessResolver grants PUBLIC only to current active members through a live retrieval-eligible Pair mapping | Capability integration with owner, member, inactive member, anonymous/unknown, foreign actor, and ineligible/deleting mapping |
| PRIVATE and SYNC are rejected until Group and permission-sync prerequisites exist | Capability and HTTP validation tests |
| Tenant → Connector → sorted Pairs → sorted index/cleanup attempts → item/version → Document → mappings lock order prevents deadlocks | Controlled PostgreSQL finalization/removal/deletion race test |
| REMOVE_ITEM cleanup removes mappings, attempts, versions/binary, item, and final unreferenced Document/version content | PostgreSQL dependency-order and restart recovery test |
| Source-delete command retries return the same CleanupAttemptId before or after target deletion | Lost-response HTTP retry in `SourceApiIntegrationTest` |
| Final Pair deletion marks Connector DELETING; later item-remove resolves the parent cleanup and subordinate cleanups become SUPERSEDED | Application transition and PostgreSQL worker cleanup integration |
| Cleanup claim/reclaim uses global lock order; only the latest token commits and already-adopted/missing targets become terminal SUPERSEDED | Multi-worker stale-token, lease-reclaim, and higher-level-cleanup test |
| Cleanup remains possible after Tenant deactivation while create/claim/finalize serialize against deactivation and cannot publish afterward | Tenant lifecycle plus worker/create/cleanup race integration |
| API and logs never expose binary bytes, extracted text, raw parser exception, claim token, or credential secret material | Problem Details assertions and captured-log scan |
| Gradle graph is `connector -> core`; MEM-35 API excludes the bundle/Tika, worker selects it at runtime, provider folders cannot import capability internals, and Modulith edges remain `ingestion -> connector, document` plus `connector -> identity, tenant, document` | Gradle dependency assertions, aggregate core Modulith test, core/adapter ArchUnit tests, MEM-35 runtime classpath check, and ADR evidence for the later shared-bundle API cost |
| Complete V5 schema, composite tenant FKs, bounded BYTEA/text constraints, idempotency keys, `SKIP LOCKED` claims, and cleanup execute on PostgreSQL | `PostgresSourceConcurrencyTest` plus PostgreSQL-backed `WorkerFileProcessingIntegrationTest`; H2 remains the fast API contract boundary |
| Real runtime indexes and removes one file through API and scheduled worker surfaces with PostgreSQL worker persistence | `SourceApiIntegrationTest`, `WorkerFileProcessingIntegrationTest`, generated client/browser flow, and production image builds |

## Implemented evidence

- `ModulithArchitectureTest`, `CoreDependencyRulesTest`, and `ProviderDependencyRulesTest` pass for six closed core capabilities and the shared provider bundle.
- `PostgresSourceConcurrencyTest` passes concurrent source creation, duplicate-byte convergence, composite tenant-FK rejection, PostgreSQL `SKIP LOCKED` lease reclaim, stale completion rejection/current completion acceptance, cleanup lock rechecks, deleting-item upload rejection, extraction-metadata persistence, and inactive-Tenant cancellation without publication.
- `TikaSourceContentExtractorTest` passes TXT, Markdown, PDF, DOCX, unsupported, encrypted, malformed, write-limit, process-timeout termination, and bounded-close behavior with misleading filenames and no provider type leakage. The child launcher uses a Java argument file, so the full runtime classpath remains operable beyond Windows command-line limits.
- `SourceApiIntegrationTest` passes OWNER lifecycle, MEMBER denial, request-body and MVC method validation, malformed JSON preservation, bounded multipart rejection, immediate PUBLIC-access invalidation, remove/delete cleanup, and lost-response delete retry. `BearerAuthenticationIntegrationTest` additionally exercises the real servlet container's multipart limit and typed 413 response.
- `WorkerFileProcessingIntegrationTest` runs the actual scheduled worker with PostgreSQL and isolated Tika extraction through create → upload → ACTIVE → remove → Tenant deactivation → successful cleanup. `WorkerApplicationSmokeTest` verifies composition with scheduling disabled.
- `OpenApiContractTest` passes against the regenerated authenticated Sources/SourceOperations contract with closed, required response schemas; the Hey API client drift gate passes.
- Frontend unit tests pass 26 scenarios, including invitation-only administration routing. The Sources UI follows Onyx's scoped-mutation and status-refresh model while aborting operation polling on unmount. Playwright passes all 15 browser scenarios, including the complete FILE Sources administration flow.
- `./gradlew.bat clean check --no-daemon` and `pnpm check` pass. API and worker Docker targets build with their target-specific extracted application jars; runtime image smoke checks confirm the worker connector classpath and both application-jar selections. Production Compose continues to gate worker startup on migrated API health.
- JetBrains inspected every changed Java, Kotlin DSL, YAML, and TOML file with warnings enabled; no inspection errors remain. Retained warnings are the conventional public Java launcher required by `java`, the existing custom `X-MemoryOS-CSRF` test header, a weak long-test extraction suggestion, and an existing test-fixture field-locality suggestion.
- PR #39 merged as `2988809517fb4855f6abd16a0af3edbbdcdbbf9c`; exact-merge CI and post-merge Playwright smoke passed, and staging API/worker/web health plus Flyway V5 were verified on exact-SHA images. Authenticated staging FILE indexing and cleanup were explicitly left for owner acceptance, so this increment remains repository-active despite Linear currently recording Done.
