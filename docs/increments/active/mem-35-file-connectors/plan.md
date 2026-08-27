# MEM-35 implementation plan: Organization-owned FILE connectors

## Contract and architecture

- [ ] Reconcile the finalized Linear contract, Onyx reference findings, this design, and current capability boundaries before implementation.
- [ ] Add MEM-35 to the repository roadmap and active-increment map without prematurely marking the unimplemented source contract as current architecture.
- [ ] Add an ADR after implementation starts for the shared `:connector` integration bundle, accepted bundle-wide dependency/CVE blast radius, provider-folder split trigger, six-capability core map, and persistence-backed worker activation.
- [ ] Add real closed `connector`, `document`, and `ingestion` Spring Modulith modules to `:core` with only the one-way dependencies in the design.
- [ ] Create `:connector` depending on `:core`; add only `io.memoryos.provider.file` in MEM-35 and keep Apache Tika/provider implementation out of core.
- [ ] Keep API depending only on core in MEM-35; update worker to core + runtime connector while API remains Flyway owner. MEM-9 later adds Google in the same bundle and composes it into API/worker.
- [ ] Expand aggregate `:core:test` Modulith verification to six capabilities and add an adapter-classpath ArchUnit test that forbids imports of capability internal packages.
- [x] Remove empty authorization, knowledge, ingestion, retrieval, and assistant core placeholders; future capabilities return only with a concrete increment.
- [ ] Add narrow core Organization ports for active-OWNER management, active lifecycle-locked index publication, inactive-safe cleanup locking, and current active-membership PUBLIC reads.
- [ ] Keep DocumentByConnectorCredentialPair in connector and expose mandatory transaction ports consumed by ingestion; connector never depends on ingestion.

## Persistence

- [ ] Add Flyway V5 with connectors, credentials, connector_credential_pairs, connector_items, connector_item_versions, index_attempts, connector_cleanup_attempts, documents, document_versions, and documents_by_connector_credential_pair.
- [ ] Put `organization_id` on every tenant-owned row and enforce composite Organization foreign keys for every live Connector/Credential/Pair/item/document association; cleanup target IDs are retained evidence validated at task creation.
- [ ] Enforce deterministic one-NO_AUTH-Credential-per-Organization and unique Connector/Credential Pair identity.
- [ ] Store FILE bytes in bounded PostgreSQL BYTEA revisions with byte-length/hash-shape constraints; add no speculative storage adapter.
- [ ] Add Pair-wide attempt sequence, Pair list/status, Connector-item, document count, reverse cleanup, pending/expired-lease claim, and cleanup-attempt query/indexes.
- [ ] Encode one-cleanup-per-target idempotency, claim-token, Organization lifecycle, global lock order ending Document → mappings, deletion/item guards, pair aggregate recomputation, and conditional updates.

## Capability contracts

- [ ] Implement Connector, Credential, ConnectorCredentialPair, ConnectorItem, source-management/access contracts, and mandatory indexing/cleanup ports in the core `connector` root package.
- [ ] Implement Document/DocumentVersion identifiers, status, command/query contracts, views, and typed failures in the core `document` root package.
- [ ] Implement IndexAttempt/CleanupAttempt queues, leases, orchestration, and SourceContentExtractor SPI in the core `ingestion` root package.
- [ ] Implement owner-authorized FILE Connector creation that creates/reuses Organization NO_AUTH Credential and returns one PUBLIC Pair.
- [ ] Implement bounded idempotent FILE upload that writes ConnectorItem revision plus NOT_STARTED IndexAttempt atomically and performs no media detection/extraction in the API request.
- [ ] Implement Pair-keyed list/detail/item-reindex/attempt/delete commands, Connector-keyed item list/remove commands, and queryable cleanup status; reindex is one selected Pair + item current version and FILE exposes exactly one Pair.
- [ ] Implement PUBLIC access resolution through current active Organization membership plus a live Pair mapping; reject PRIVATE and SYNC until prerequisites exist and add no retrieval endpoint.

## Extraction and worker

- [ ] Add Apache Tika 3 only to the shared `:connector` module's FILE provider folder; implement the core ingestion `SourceContentExtractor` SPI without exposing Tika types.
- [ ] Detect actual media type and allow only PDF, DOCX, UTF-8 TXT, and Markdown; accept allowed bytes despite a missing/misleading extension.
- [ ] Disable OCR, macros, archive recursion, and embedded attachment extraction; cap upload at 10 MiB and normalized text at 2,000,000 characters.
- [ ] Add total/progress extraction limits and safe typed outcomes for encrypted, malformed, unsupported, timeout, and write-limit failures.
- [ ] Implement deterministic PostgreSQL `FOR UPDATE SKIP LOCKED` claim/reclaim for NOT_STARTED or expired IN_PROGRESS leases with fresh claim tokens; cancel work when the owning Organization is inactive.
- [ ] Extract outside database transactions, then finalize only when claim token, Pair, item, and current-version guards pass; recompute Pair aggregate state and cancel obsolete/deleting work.
- [ ] Make `worker` persistence-backed with JDBC/PostgreSQL, core runtime, shared connector bundle, readiness, graceful shutdown, and bounded container resources.
- [ ] Keep API as Flyway owner and start worker only after migrated API health; API excludes the connector/Tika bundle in MEM-35, with the later MEM-9 bundle-wide classpath cost explicitly accepted.

## HTTP and product surface

- [ ] Publish consumer-facing `/api/sources` create/list/detail/upload/item/reindex/delete endpoints and `/api/source-operations/{operationId}` polling; map sourceId internally to Pair without exposing ConnectorCredentialPair as HTTP taxonomy.
- [ ] Add safe RFC 9457 outcomes for synchronous authority, cross-Organization, multipart, size, lifecycle, conflict, and durable-write failures; expose media/extraction failures asynchronously through safe attempt/Pair status.
- [ ] Project `SOURCES_MANAGE` only for active Organization OWNER while retaining durable authority checks for every command.
- [ ] Generate the OpenAPI snapshot and TypeScript client from the live Spring MVC contract.
- [ ] Build the Onyx-shaped Sources flow: connector type, fixed NO_AUTH credential context, configuration, PUBLIC access, Source status card/detail, items, attempts, and errors.
- [ ] Poll Source detail while indexing and the returned SourceOperation after delete; treat SUCCEEDED/SUPERSEDED as terminal success and add no WebSocket/SSE infrastructure.
- [ ] Keep binary bytes, extracted text, claims, secret references, parser failures, and sensitive filenames out of responses and logs.

## Concurrency, cleanup, and authorization

- [ ] Prove concurrent FILE Connector creation converges on one Organization NO_AUTH Credential without cross-Organization reuse.
- [ ] Prove duplicate upload bytes in the one MEM-35 FILE Pair converge on one ConnectorItem/version and one Pair-specific attempt/mapping; defer executable multi-Pair convergence to MEM-9/MEM-10.
- [ ] Prove worker replicas may re-extract after lease expiry but only the current claim token can finalize; crash/restart converges.
- [ ] Prove Pair-wide sequence makes overlapping/CANCELLED attempts deterministic and an older late completion cannot hide pending/newer work.
- [ ] Prove concurrent item reindex returns one nonterminal attempt, allocates Pair/item sequences atomically, and never treats unrelated items as obsolete.
- [ ] Prove failed extraction creates no partial searchable Document/version or stale Pair count.
- [ ] Prove Organization → Connector → sorted Pairs → sorted index/cleanup attempts → item/version → Document → mappings lock order prevents finalization/removal/deletion deadlocks.
- [ ] Prove DELETE retries return the same CleanupAttemptId, final Pair deletion marks Connector DELETING, later item removal returns the parent cleanup without creating a child, and earlier child cleanups become SUPERSEDED.
- [ ] Prove cleanup lease reclaim accepts only the latest claim token, treats already-adopted targets as terminal SUPERSEDED, and continues after Organization deactivation without creating content/access.
- [ ] Prove same-Organization Pair/item mismatches and every live cross-Organization association fail; cleanup evidence IDs are created only from already locked same-Organization targets.
- [ ] Prove MEMBER cannot manage sources while SourceDocumentAccessResolver grants PUBLIC only through current active Organization membership and a live mapping.
- [ ] Prove inactive Organization work is cancelled at claim/finalization without mutating NO_AUTH Credential lifecycle or publishing Documents.

## Verification and delivery

- [ ] Inspect every changed Java, Kotlin DSL, YAML, properties, and XML file with JetBrains warnings enabled.
- [ ] Compile core, API, and worker after static inspection.
- [ ] Run focused capability persistence, PostgreSQL concurrency, worker-runtime, API authority, multipart, OpenAPI, and browser tests.
- [ ] Exercise the actual API create/upload/status/remove/delete path with a real worker and PostgreSQL.
- [ ] Exercise PDF, DOCX, TXT, Markdown, unsupported, oversized, encrypted, malformed, timeout, and duplicate-file outcomes.
- [ ] Run the complete V5 schema, expression/partial indexes, composite tenant FKs, hostile constraint cases, claims, and cleanup against PostgreSQL; use H2 only for fast behavior that does not depend on PostgreSQL semantics.
- [ ] Run `gradlew.bat clean check --no-daemon`, `pnpm check`, the browser contract suite, and production image builds.
- [ ] Deploy one reviewed SHA and prove API/worker health plus one complete FILE Connector indexing and cleanup flow without secret/content leakage.
- [ ] Consolidate implemented facts into architecture, connector/document/ingestion specs, verification matrices, runbooks, roadmap, and increment evidence in the same substantive change.
- [ ] Complete the guarded PR/CodeRabbit/latest-head CI/merge-SHA loop, update MEM-35, and clean the checkout.
