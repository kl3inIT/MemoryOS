# MEM-35 verification matrix: Organization-owned FILE connectors

This record defines required evidence before the increment can merge. Test names are assigned during implementation and must describe observable contracts rather than source structure.

| Requirement | Required evidence |
| --- | --- |
| Only an active Organization OWNER can create, inspect, upload to, reindex, remove from, or delete a FILE source | Capability test through real transaction proxies plus HTTP integration with OWNER, MEMBER, inactive membership, and inactive Organization |
| Connector creation creates/reuses exactly one Organization NO_AUTH Credential and one PUBLIC ConnectorCredentialPair | PostgreSQL concurrent-create and uniqueness test |
| NO_AUTH contains no provider account, scopes, token, secret reference, or refresh behavior | Persistence assertion, API redaction assertion, and log capture |
| Pair identity is unique and composite FKs reject same-Organization Connector/item mismatches plus every live cross-Organization association; cleanup target evidence is same-Organization at creation | Full V5 PostgreSQL migration, hostile inserts, and repository/API cleanup creation scenarios |
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
| Organization → Connector → sorted Pairs → sorted index/cleanup attempts → item/version → Document → mappings lock order prevents deadlocks | Controlled PostgreSQL finalization/removal/deletion race test |
| REMOVE_ITEM cleanup removes mappings, attempts, versions/binary, item, and final unreferenced Document/version content | PostgreSQL dependency-order and restart recovery test |
| Pair deletion dominates attempt results and every DELETE retry returns the same CleanupAttemptId before or after target hard deletion | PostgreSQL deletion/finalization race plus lost-response HTTP retry flow |
| Final Pair deletion marks Connector DELETING; subsequent item DELETE returns the parent CleanupAttemptId and creates no new child, while earlier child cleanup becomes SUPERSEDED | PostgreSQL overtaking race with already-claimed, pending, and post-parent item deletion |
| Cleanup claim/reclaim uses global lock order; only the latest token commits and already-adopted/missing targets become terminal SUPERSEDED | Multi-worker stale-token, lease-reclaim, and higher-level-cleanup test |
| Cleanup remains possible after Organization deactivation while create/claim/finalize serialize against deactivation and cannot publish afterward | Organization lifecycle plus worker/create/cleanup race integration |
| API and logs never expose binary bytes, extracted text, raw parser exception, claim token, or credential secret material | Problem Details assertions and captured-log scan |
| Gradle graph is `connector -> core`; MEM-35 API excludes the bundle/Tika, worker selects it at runtime, provider folders cannot import capability internals, and Modulith edges remain `ingestion -> connector, document` plus `connector -> identity, organization, document` | Gradle dependency assertions, aggregate core Modulith test, core/adapter ArchUnit tests, MEM-35 runtime classpath check, and ADR evidence for the later shared-bundle API cost |
| Complete V5 schema, JSONB, partial/expression indexes, constraints, claims, and cleanup work on PostgreSQL | Dedicated production-migration integration suite; H2 is not accepted as schema proof |
| Real runtime indexes and removes one file through API + worker + PostgreSQL | Smoke evidence for create → upload → poll ACTIVE → resolve PUBLIC access → remove/delete, recorded against reviewed SHA |
