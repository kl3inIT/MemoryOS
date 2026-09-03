# MEM-35 implementation plan: Tenant-owned FILE connectors

## Contract and architecture

- [x] Reconcile the finalized Linear contract, Onyx reference findings, this design, and current capability boundaries before implementation.
- [x] Add MEM-35 to the repository roadmap and active-increment map without prematurely marking the unimplemented source contract as current architecture.
- [x] Add an ADR after implementation starts for the shared `:connector` integration bundle, accepted bundle-wide dependency/CVE blast radius, provider-folder split trigger, six-capability core map, and persistence-backed worker activation.
- [x] Add real closed `connector`, `document`, and `ingestion` Spring Modulith modules to `:core` with only the one-way dependencies in the design.
- [x] Create `:connector` depending on `:core`; add only `io.memoryos.provider.file` in MEM-35 and keep Apache Tika/provider implementation out of core.
- [x] Keep API depending only on core in MEM-35; update worker to core + runtime connector while API remains Flyway owner. MEM-9 later adds Google in the same bundle and composes it into API/worker.
- [x] Expand aggregate `:core:test` Modulith verification to six capabilities and add an adapter-classpath ArchUnit test that forbids imports of capability internal packages.
- [x] Remove empty authorization, knowledge, ingestion, retrieval, and assistant core placeholders; future capabilities return only with a concrete increment.
- [x] Add narrow core Tenant ports for active-OWNER management, active lifecycle-locked index publication, inactive-safe cleanup locking, and current active-membership PUBLIC reads.
- [x] Keep DocumentByConnectorCredentialPair in connector and expose mandatory transaction ports consumed by ingestion; connector never depends on ingestion.
- [x] Keep `DefaultSourceManagementService` limited to authorization, validation, orchestration, transaction boundaries, transition decisions, and typed failures; no SQL, `JdbcClient`, row mapping, locks, claims, or bulk updates.
- [x] Implement concrete `JdbcSourceRepository`, `JdbcSourceItemRepository`, `JdbcIndexAttemptRepository`, `JdbcSourceDocumentRepository`, and `JdbcSourceQueryRepository` under `connector.persistence`, grouped by consistency/use-case boundary with no single-implementation repository interfaces.
- [x] Keep MEM-35 JDBC-first; add no JPA, Querydsl, or jOOQ, migrate no existing invitation/Tenant repository for style, and reevaluate JPA only when MEM-36 Groups provides concrete relationship-lifecycle value.

## Persistence

- [x] Add Flyway V5 with connectors, credentials, connector_credential_pairs, connector_items, connector_item_versions, index_attempts, connector_cleanup_attempts, documents, document_versions, and documents_by_connector_credential_pair.
- [x] Put `tenant_id` on every tenant-owned row and enforce composite Tenant foreign keys for every live Connector/Credential/Pair/item/document association; cleanup target IDs are retained evidence validated at task creation.
- [x] Enforce deterministic one-NO_AUTH-Credential-per-Tenant and unique Connector/Credential Pair identity.
- [x] Store FILE bytes in bounded PostgreSQL BYTEA revisions with byte-length/hash-shape constraints; add no speculative storage adapter.
- [x] Add Pair-wide attempt sequence, Pair list/status, Connector-item, document count, reverse cleanup, pending/expired-lease claim, and cleanup-attempt query/indexes.
- [x] Encode one-cleanup-per-target idempotency, claim-token, Tenant lifecycle, global lock order ending Document → mappings, deletion/item guards, pair aggregate recomputation, and conditional updates.

## Capability contracts

- [x] Implement Connector, Credential, ConnectorCredentialPair, ConnectorItem, source-management/access contracts, and mandatory indexing/cleanup ports in the core `connector` root package.
- [x] Implement Document/DocumentVersion identifiers, status, command/query contracts, views, and typed failures in the core `document` root package.
- [x] Implement IndexAttempt/CleanupAttempt queues, leases, orchestration, and SourceContentExtractor SPI in the core `ingestion` root package.
- [x] Implement owner-authorized FILE Connector creation that creates/reuses Tenant NO_AUTH Credential and returns one PUBLIC Pair.
- [x] Implement bounded idempotent FILE upload that writes ConnectorItem revision plus NOT_STARTED IndexAttempt atomically and performs no media detection/extraction in the API request.
- [x] Implement Pair-keyed list/detail/item-reindex/attempt/delete commands, Connector-keyed item list/remove commands, and queryable cleanup status; reindex is one selected Pair + item current version and FILE exposes exactly one Pair.
- [x] Implement PUBLIC access resolution through current active Tenant membership plus a live Pair mapping; reject PRIVATE and SYNC until prerequisites exist and add no retrieval endpoint.

## Extraction and worker

- [x] Add Apache Tika 4.0.0 minimal modules only to the shared `:connector` module's FILE provider folder; implement the core ingestion `SourceContentExtractor` SPI without exposing Tika types.
- [x] Detect actual media type and allow only PDF, DOCX, UTF-8 TXT, and Markdown; accept allowed bytes despite a missing/misleading extension.
- [x] Disable OCR, macros, archive recursion, and embedded attachment extraction; cap upload at 10 MiB and normalized text at 2,000,000 characters.
- [x] Add total/progress extraction limits and safe typed outcomes for encrypted, malformed, unsupported, timeout, and write-limit failures.
- [x] Implement deterministic PostgreSQL `FOR UPDATE SKIP LOCKED` claim/reclaim for NOT_STARTED or expired IN_PROGRESS leases with fresh claim tokens; cancel work when the owning Tenant is inactive.
- [x] Extract outside database transactions, then finalize only when claim token, Pair, item, and current-version guards pass; recompute Pair aggregate state and cancel obsolete/deleting work.
- [x] Make `worker` persistence-backed with JDBC/PostgreSQL, core runtime, shared connector bundle, readiness, graceful shutdown, and bounded container resources.
- [x] Keep API as Flyway owner and start worker only after migrated API health; API excludes the connector/Tika bundle in MEM-35, with the later MEM-9 bundle-wide classpath cost explicitly accepted.

## HTTP and product surface

- [x] Publish consumer-facing `/api/sources` create/list/detail/upload/item/reindex plus command-style POST remove/delete endpoints and `/api/source-operations/{operationId}` polling; map sourceId internally to Pair without exposing ConnectorCredentialPair as HTTP taxonomy.
- [x] Add Boot-managed validation only to `:api`; use `@Valid` and MVC-native method constraints without controller `@Validated`, and add narrow safe handlers for both request-body and method validation while preserving Boot Problem Details for all other framework failures.
- [x] Add safe RFC 9457 outcomes for synchronous authority, cross-Tenant, multipart, size, lifecycle, conflict, and durable-write failures; expose media/extraction failures asynchronously through safe attempt/Pair status.
- [x] Project `SOURCES_MANAGE` only for active Tenant OWNER while retaining durable authority checks for every command.
- [x] Generate the OpenAPI snapshot and TypeScript client from the live Spring MVC contract.
- [x] Build the Onyx-shaped Sources flow: connector type, fixed NO_AUTH credential context, configuration, PUBLIC access, Source status card/detail, items, attempts, and errors.
- [x] Poll Source detail while indexing and the returned SourceOperation after delete; treat SUCCEEDED/SUPERSEDED as terminal success and add no WebSocket/SSE infrastructure.
- [x] Keep binary bytes, extracted text, claims, secret references, parser failures, and sensitive filenames out of responses and logs.

## Concurrency, cleanup, and authorization

- [x] Prove concurrent FILE Connector creation converges on the deployment Tenant's one NO_AUTH Credential.
- [x] Prove duplicate upload bytes in the one MEM-35 FILE Pair converge on one ConnectorItem/version and one Pair-specific attempt/mapping; defer executable multi-Pair convergence to MEM-9/MEM-10.
- [x] Prove worker replicas may re-extract after lease expiry but only the current claim token can finalize; crash/restart converges.
- [x] Prove Pair-wide sequence makes overlapping/CANCELLED attempts deterministic and an older late completion cannot hide pending/newer work.
- [x] Prove concurrent item reindex returns one nonterminal attempt, allocates Pair/item sequences atomically, and never treats unrelated items as obsolete.
- [x] Prove failed extraction creates no partial searchable Document/version or stale Pair count.
- [x] Prove Tenant → Connector → sorted Pairs → sorted index/cleanup attempts → item/version → Document → mappings lock order prevents finalization/removal/deletion deadlocks.
- [x] Prove source-delete command retries return the same CleanupAttemptId, final Pair deletion marks Connector DELETING, later item removal returns the parent cleanup without creating a child, and earlier child cleanups become SUPERSEDED.
- [x] Prove cleanup lease reclaim accepts only the latest claim token, treats already-adopted targets as terminal SUPERSEDED, and continues after Tenant deactivation without creating content/access.
- [x] Prove same-Tenant Pair/item mismatches fail; MEM-24 later database-enforces the one-Tenant deployment invariant.
- [x] Prove MEMBER cannot manage sources while SourceDocumentAccessResolver grants PUBLIC only through current active Tenant membership and a live mapping.
- [x] Prove inactive Tenant work is cancelled at claim/finalization without mutating NO_AUTH Credential lifecycle or publishing Documents.

## Verification and delivery

- [x] Inspect every changed Java, Kotlin DSL, YAML, properties, and XML file with JetBrains warnings enabled.
- [x] Compile core, API, and worker after static inspection.
- [x] Run focused capability persistence, PostgreSQL concurrency, worker-runtime, API authority, multipart, OpenAPI, and browser tests.
- [x] Exercise the actual API create/upload/status/remove/delete path with a real worker and PostgreSQL.
- [x] Exercise PDF, DOCX, TXT, Markdown, unsupported, oversized, encrypted, malformed, timeout, and duplicate-file outcomes.
- [x] Run the complete V5 schema, composite tenant FKs, bounded BYTEA/text constraints, idempotency keys, `SKIP LOCKED` claims, hostile tenant associations, and cleanup against PostgreSQL; use H2 only for fast framework/API behavior.
- [x] Run `gradlew.bat clean check --no-daemon`, `pnpm check`, the browser contract suite, and production image builds.
- [ ] Deploy one reviewed SHA and prove API/worker health plus one complete FILE Connector indexing and cleanup flow without secret/content leakage.
- [x] Consolidate implemented facts into architecture, connector/document/ingestion specs, verification matrices, runbooks, roadmap, and increment evidence in the same substantive change.
- [x] Complete the guarded PR/CodeRabbit/latest-head CI/merge-SHA loop, update MEM-35, and clean the checkout.
