# MemoryOS architecture

This document describes the system implemented in this repository. Product intent lives in [docs/vision.md](docs/vision.md); accepted rationale lives in [ADRs](docs/decisions/).

## System shape

MemoryOS is a controlled Spring Modulith monolith with four flat Gradle modules:

| Module | Runtime role | Dependency rule |
| --- | --- | --- |
| `core` | Seven closed capability implementations: contracts, transactions, capability-owned persistence, and the object-storage S3 adapter | Must not depend on `connector` or a deployable |
| `connector` | Shared Google network integration and offline native/table/binary extraction bundle | Depends only on public `core` APIs |
| `api` | Spring Boot HTTP, Google consent, migration, and security composition root | Depends on `core` and the shared `connector` bundle; extraction is worker-composed |
| `worker` | PostgreSQL-authoritative Redis Stream execution and control-plane composition root | Depends on `core`, selects `connector` at runtime, and alone composes Redis/db-scheduler |

`api` and `worker` are separate deployables. API owns Flyway and durable source/upload/selection commands. Browser FILE bytes travel by checksum-bound presigned PUT directly to object storage, never through API request bodies. API transactions commit ingestion, source-sync and cleanup operations without contacting Redis. Worker db-scheduler relays eligible identifiers into workload-specific Redis Streams; consumers reload and token-claim authoritative PostgreSQL operations, renew leases, and finalize durably before acknowledgement. SOURCE_SYNC acquires Google content into tracked immutable raw snapshots; INGESTION reads stored input without Google network access. PostgreSQL is business authority, Redis is rebuildable delivery state, and MinIO owns raw inputs plus canonical extraction artifacts.

`web/` is a separate production deployable built with Vite, React, TanStack Router, TanStack Query, Tailwind CSS, and generated Hey API clients. It is not a Gradle module or a reusable package. The Nginx runtime serves immutable assets and owns the browser origin; Spring remains the API, OAuth2, session, and authorization runtime.

The pathless authenticated route owns `ApplicationSessionBoundary`, resolves `/api/identity/me` for protected children and provides one application-session context. The persistent administration `AppShell` gates invitation surfaces with `INVITATIONS_MANAGE` and Sources with `SOURCES_MANAGE`; a denied deep link mounts no protected query. Home offers only capability-authorized routes, not a disabled chat composer or a new chat capability.

`/admin` groups configured Sources in a semantic table with search, provider/status/access filters and expandable provider summaries. Access is read-only: FILE PUBLIC means active workspace-member access, not public Internet access. Each Source opens `/admin/sources/{sourceId}`. The searchable catalog at `/admin/sources/new` offers only FILE and Google Drive.

FILE setup at `/admin/sources/new/file` remains one name-and-file form with drag/drop, validation and one Upload and create action. Its one-step presentation does not add a backend phase. Upload retries retain the created Source; finalization retries reuse the stored upload without another PUT. Pending finalization identifiers survive Source route navigation in the administration layout. Detail supports checksum-bound direct PUT, progress/cancellation, indexing/cleanup polling and safe reindex/remove/delete commands without exposing extracted content.

Source detail and FILE creation return a direct `SourceSummary`; Files use a separate bounded keyset page. The UI renders only the current page, restores Previous/Next navigation, resets to the first page after upload/finalization and preserves exact reindex/removal observation across page changes. `JdbcSourceQueryRepository` bounds item candidates before version/latest-attempt projection and successful completion for the current version. `lastIndexedAt` is independent of original item creation; `latestAttempt` carries actual file-processing timestamps and outcome. Summary polling never materializes the item corpus. See the [Source read contract](docs/specs/connector.md#source-summaries-and-files-pages).

Google setup at `/admin/sources/new/google-drive` presents Credential → Connector. The owner selects a reusable credential or authorizes one with owner-supplied Web OAuth client JSON; callback selects a credential, not a Source. Connector submission returns a durable selection receipt and reserved Source ID without waiting for Google. The worker verifies and atomically activates Source/roots/initial sync; the UI waits for success before Source navigation and can recover a lost receipt. Detail separates active and pending selection. Lazy Source-scoped tree reads expose actual folder children and retained file-link provenance; filtered reads remain file-ID-unique. Select for sync loads the complete pinned draft without persisting approval; its Save/Cancel controls appear below the tree independently of the root editor. The File and folder links disclosure below the tree contains Edit selection and the editor, opened only by explicit Edit; switching to it preserves draft approvals. Technical paths and the persistent selection-count subtitle are absent. Drive Indexing attempts consumes actual Source runs in a compact table, while FILE has separately named per-file processing history. The old multi-card run dashboard remains absent. OAuth app/redirect instructions stay collapsed; reconnect/revoke and conflicts preserve current-authority guards. No account-wide browser or document-read grant is introduced; Google remains RESTRICTED.

`SourceSummaryCard` shares the four existing summary fields between FILE and Google Drive; Google Drive adds Automatic interval/Edit as a fifth field while keeping schedule state in `GoogleDrivePanel`. Synchronization actions remain below the card. FILE processing-history pages include an exact Tenant/Source-scoped `totalItems` from `JdbcIndexAttemptRepository`; current/total pages cover retained attempts independently of the cursor. Drive run history uses cursor navigation without inventing a total count. Both histories default to 5 rows with 5/10/25/50 choices, resetting to the first page when size or Source changes.

Application-owned links use TanStack Router and preserve the browser document; OAuth2, provider logout, invitation continuation, mail, and fragment navigation remain native. The QueryClient retains an accepted actor/authority fingerprint across route remounts, refetches identity whenever the browser returns to the foreground, and purges non-identity queries plus mutations before accepting changed authority or converging any private `401` to signed-out state. Invitation creation is a semantic single-flight form. Vite emits imported fonts as same-origin assets, and the build rejects inline `data:font` URLs to remain compatible with the production `font-src 'self'` policy.

The web design system remains local to this application and adopts MIT-licensed Onyx/Opal presentation without importing its backend capabilities or Enterprise code. `styles/tokens.css` owns stone/monochrome primitives and semantic roles, `styles/theme.css` maps them and Hanken Grotesk typography into Tailwind v4, and `styles/base.css` owns global behavior. Shared `SettingsLayout`/`PageHeader`, sidebar, menu, form and state primitives align existing Source, invitation, session and access screens across narrow layouts and light/dark themes. MemoryOS branding remains; substantial copied code/assets retain attribution in the shipped `THIRD_PARTY_NOTICES.txt`. No reusable design-system package or parallel migration component tree is introduced.

## Capability boundaries

`core` contains seven implemented closed Spring Modulith modules: `identity`, `tenant`, `invitation`, `objectstorage`, `connector`, `document`, and `ingestion`. A capability root package is its public API; application services own authorization, validation, orchestration, and transaction boundaries, while concrete `persistence` repositories own SQL, row mapping, locks, claims, conditional transitions, and bulk operations.

`tenant` depends only on `identity`. `invitation` depends on public `identity` and `tenant`. `objectstorage` depends on `tenant`, owns generic object/upload/server-write persistence, and contains its S3 adapter without exposing AWS SDK types. `document` depends on public `tenant` and `objectstorage` and owns current metadata plus extraction-artifact lifecycle. `connector` depends on public `identity`, `tenant`, `document`, and `objectstorage`; it owns Source/Credential/Item provenance, Google selection and synchronization authority, browser upload receipts, raw-input adoption and cleanup. `ingestion` consumes these public APIs for asynchronous orchestration. Spring Modulith and ArchUnit reject cycles, cross-capability persistence imports, deployable dependencies, and provider imports of capability internals.

`api` scans `io.memoryos`, composes fixed Tenant resolution, and verifies the configured UUID against active Tenant persistence. Worker scans its composition and required capability packages without API/security or Arconia web composition. Every durable ingestion, sync and cleanup record carries the explicit `TenantId` used by repository predicates. Redis and db-scheduler remain worker composition concerns rather than capability dependencies.

Audit is intentionally absent until a real evidence consumer defines attribution, transaction, retention, access, and export semantics. See [ADR 0003](docs/decisions/0003-defer-audit-until-evidence-consumer.md).

## Persistence and startup

Flyway owns nineteen migrations:

- `V1__create_identity_tables.sql`: stable `actors` and exact `(issuer, subject)` bindings.
- `V2__create_initial_organization_and_sessions.sql`: historical Organization/default-Workspace schema and Spring Session JDBC tables.
- `V3__create_organization_invitations.sql`: historical Invitation lifecycle initially scoped by Organization/default Workspace.
- `V4__collapse_workspace_into_organization.sql`: removes the default-Workspace layer and makes Organization the direct historical owner.
- `V5__create_file_source_and_document_schema.sql`: historical Organization-scoped Connector/Credential/Pair/item/attempt/Document/provenance/cleanup state.
- `V6__cut_over_organization_to_tenant.sql`: renames the active schema to Tenant, preserves UUIDs and composite ownership, and enforces one `deployment_slot = 1` Tenant row.
- `V7__create_scheduler_control_plane.sql`: db-scheduler's PostgreSQL control-plane table and execution/heartbeat indexes; it contains no Tenant or business-operation authority.
- `V8__cut_over_operations_to_redis_streams.sql`: adds stable delivery identity, dispatch claims/evidence, rediscovery timing, transport diagnostics, and processing-attempt counters to index and cleanup operations; it removes direct-poller claim indexes.
- `V9__cut_over_file_content_to_object_storage.sql`: creates generic stored-object/upload state and Connector-owned Source upload receipts, removes FILE `BYTEA`, and makes each item version own one restrictive `StoredObject` reference.
- `V10__persist_operation_trace_origins.sql`: nullable operation-origin tracing identifiers.
- `V11__add_document_extraction_artifacts.sql`: tracked extracted-output writes, adoption and cleanup.
- `V12__use_current_documents.sql`: current metadata/artifact reference in place; removes Document history and stored normalized text.
- `V13__add_tracked_object_writes.sql`: server-side raw-write reservations, writer/cleanup fencing, and separate binary/native storage limits.
- `V14__add_google_drive_credentials.sql`: encrypted Google grants with separate authority and refresh-payload revisions.
- `V15__add_durable_google_drive_sync.sql`: selected roots, input provenance, durable sync/frontier/checkpoints, and reconciliation deferral accounting.
- `V16__require_owner_google_oauth_client.sql`: removes legacy shared-app grants/continuations and fences obsolete work while preserving Sources, roots, items and stored content; requires owner-app reauthorization.
- `V17__scope_google_sync_to_explicit_roots.sql`: removes account-wide Changes cursor/replay state and retains durable selected-root reconciliation.
- `V18__reuse_google_drive_credentials.sql`: adds credential names, removes Google singleton constraints and indexes shared credential attachments; preserves existing identities, encrypted grants, roots and content while retaining the NO_AUTH singleton.
- `V19__add_google_drive_sync_interval.sql`: adds each Google Source's positive minute interval and independent schedule revision, defaulting existing rows to 5 minutes/revision 1 without changing their already-scheduled due timestamps.
- `V20__add_google_drive_scope_mode.sql`: persists GENERAL/SPECIFIC scope, defaulting existing Sources to SPECIFIC without changing roots, credentials, schedules, Items or Documents.
- `V21__add_google_drive_linked_documents.sql`: adds independent discovery authority and separate candidate, provenance, approval and per-input error tables; existing scope, roots, credential and scheduling state are unchanged.
- `V22__add_google_drive_selection_operations.sql`: durable proposed selection, request receipts, policy/authority snapshots, metadata/ancestor checkpoints and fenced verification claims before atomic activation.
- `V23__add_source_run_history.sql`: nullable legacy history facts, exact owned-index attribution, durable counters/safe errors, run completion and indexed bounded history/retention reads.
- `V24__add_source_item_pagination.sql`: Source item keyset and latest-attempt indexes for bounded Files reads; no existing Source or content state changes.

API startup requires datasource, OIDC, confidential browser-client, object-storage, and initial Tenant configuration including `MEMORYOS_TENANT_ID`. After migration, an `ApplicationRunner` locks the singleton bootstrap row, resolves or creates the exact owner binding, inserts or verifies the configured Tenant UUID, grants Tenant `OWNER`, and publishes the same UUID. Concurrent replicas serialize on that row. Identical configuration replays; UUID, owner, authority, lifecycle, or descriptive drift fails startup.

The worker composes a two-thread db-scheduler control plane over PostgreSQL. Topology reconciliation maintains INGESTION, SOURCE_SYNC, GOOGLE_DRIVE_SELECTION_VALIDATION and CLEANUP Redis Streams/groups. Bounded tasks enqueue due Google Sources, relay each workload, cancel inactive-Tenant work, maintain run retention, and clean abandoned browser uploads, tracked raw writes and unreferenced extraction artifacts. `scheduled_tasks` owns control-task heartbeat/recovery, not business authority. Worker readiness requires Redis, datasource, scheduler and the private storage sentinel; API readiness remains independent of Redis.

Both deployables run on Java 25 with Spring Boot virtual threads enabled and `spring.main.keep-alive=true`. Spring-managed request, asynchronous-task, and scheduling execution uses virtual threads. db-scheduler uses a named virtual thread per control-task execution while its configured `threads = 2` bounds concurrent control work. The worker owns one long-lived consumer loop per workload and a virtual thread per claimed delivery; workload batch sizes plus database and Redis connection pools bound downstream concurrency. Long-lived db-scheduler polling/housekeeping, Lettuce/Netty event loops, and datasource housekeeping remain library-managed platform threads.

Arconia's bootstrap profile is `development` for both deployables. The development API owns PostgreSQL Dev Services on host port `55432`; the worker connects to that database and owns Redis Dev Services on `56379`. Redis-dependent worker integration fixtures own JUnit containers and mapped ports independently of Arconia/CI activation; exact migration and multi-instance tests retain isolated containers. No cross-application Testcontainers reuse contract exists. Fixed development ports bind through Testcontainers on Docker host interfaces rather than a configurable loopback address, requiring a host firewall on non-private networks. Production artifacts exclude Dev Services and Testcontainers.

Execution has one cut-over path: PostgreSQL operation → db-scheduler relay → workload Redis Stream/group → identifier-scoped PostgreSQL claim and lease → durable terminal transition → XACK/XDEL. Dispatch evidence rediscoveries rebuild nonterminal work after Redis loss; a message is reclaimed only when Redis idle time and PostgreSQL lease state both permit it. No direct PostgreSQL business poller, alternate dispatcher, execution-mode switch, or compatibility path remains.

### Google synchronization and publication

Each Google credential pins its provider subject and may serve multiple Sources in the same Tenant. Sources retain separate Connector/Pair, scope, progress and item/Document identity. SPECIFIC accepts nonoverlapping explicit roots within the backend selection policy (default 1,000 roots/3 MiB request/500 linked approvals), not a fixed UI cap of 20. Those defaults are configured bounds, not Google capacity proof. GENERAL verifies and stores the actual My Drive root internally, never presenting it as a user-selected link. SOURCE_SYNC reconciles persisted roots/descendants through the same frontier, paging, snapshot and indexing path. It does not enumerate Shared with me, Shared Drives, other accounts or account-wide Changes; Specific supports explicit files/folders inside Shared Drives. Whole-drive links, shortcuts and overlapping Specific roots fail closed.

Specific Sources support explicit bounded content-link discovery. The provider bundle reads native/binary content offline; application publication stores candidates and actual parent/location provenance separately from approvals under Source/credential/discovery authority. Discovery does not synchronize new targets. An accepted root/approved-ID proposal becomes active only after checkpointed worker verification and fenced atomic activation; approved exact-file IDs then seed the same SOURCE_SYNC frontier without folder authority. Duplicate origins share Source/file identity; retained approvals remain removable after provenance disappears. Paged selection pins scope/discovery/credential authority, while status polling returns summary counts and the pending operation instead of full trees. Complete bounded drafts prevent paging from dropping selections. Limits and HTTP contracts are canonical in the [Connector contract](docs/specs/connector.md).

General revalidates the stored My Drive root against the current provider session before traversal and again before pruning. A missing, changed, trashed, shared-drive or otherwise unverifiable root fails the run rather than proving an empty scope. An incomplete generation releases successfully adopted, confirmed inputs for indexing but does not prune unseen items. Scope mode is immutable after creation: service validation rejects a mismatch before provider access, and root persistence guards the saved mode without updating it. Specific link replacement advances the scope revision and fences obsolete acquisition/publication; only complete reconciliation under the new authority can prune excluded content. Owners configure the per-Source interval with an independent schedule revision; changing the interval does not fence work or invalidate Documents, and changing links does not overwrite the interval. Minute-level due checks and manual sync remain unchanged.

Tracked raw writes reserve ownership before external PUT, verify integrity outside SQL transactions, and adopt with input/version/attempt creation in one fenced transaction. Uncertain writes retain cleanup tombstones against late PUT. INGESTION routes native Sheets/Docs snapshots offline, XLSX/CSV through Java readers, PDF/DOCX/PPTX through Docling, and UTF-8 text through bounded Tika; Slides are acquired as PPTX. Numeric bounds and structural contracts live in the [Ingestion contract](docs/specs/ingestion.md).

Publication checks current input, source, selection, credential authority and claim. Normal refresh-token rotation changes only payload revision; revocation or reauthorization changes authority. Reconciliation can temporarily defer an otherwise-current in-flight index attempt until membership is confirmed without consuming its extraction retry budget. Removed, excluded, superseded or revoked input cannot use that deferral path to publish. Google remains excluded from the existing FILE PUBLIC resolver; this is ingestion correctness, not source-ACL implementation.

Shared reconnect, revoke and accepted authentication failure fence pending selection intents, sync/index work and retrieval mappings. Authority-sensitive locks follow Tenant → credential → Source with deterministic attached-Source ordering. Selection verification checkpoints metadata/ancestor traversal outside provider transactions, serializes claims per credential and uses finite batch/operation budgets. Only activation rechecks owner/credential/Source/discovery authority and a live claim before persistence. Source cleanup retains the reusable credential; explicit credential deletion requires no attachments and the current authority revision, and invalidates pending creates.

### Durable synchronization history

`source_sync_attempts` is the run anchor; automatic `index_attempts.source_sync_attempt_id` is assigned at exact input adoption, not reconstructed from time or current item state. A later run seeing old pending work does not own its publication. Acquisition termination remains independent from end-to-end completion of owned indexing children. Durable per-file/child transitions settle counters once across retries, replay, cancellation, supersession and item cleanup; deferral does not settle a child. Legacy unrecorded facts remain Unknown. Later manual reindex cannot rewrite closed history.

Owner-authorized run list/detail/error queries return bounded keyset pages and separate Current activity, Last completed and Last successful summaries. Item-attempt history and generic operation polling remain different contracts. Safe provider/storage/extraction/publication errors are retained without exceptions, tokens or object paths. Default detail/summary retention is 14/90 days with bounded compaction, visible expiry and protection for live work, current references and unresolved failures; source deletion follows dependency order. Metrics are not history authority. Exact semantics and APIs are in the [Connector history contract](docs/specs/connector.md#synchronization-run-history). The [verification matrix](docs/tests/connector.md#measured-selection-and-history-capacity--2026-09-08) separates measured provider-port root/history capacity from actual isolated HTTP-fixture/MinIO/worker/browser evidence; neither proves live Google capacity or 100,000-file traversal. The [active increment](docs/increments/active/google-drive-structured-ingestion/plan.md#mem-76-verification--2026-09-08) tracks the pending final backend/corpus results.

## Authentication

The API composes three ordered security chains:

1. Exact Google acquisition callback `/login/oauth2/code/google-drive`: no session creation; the callback controller validates the existing owner-bound consent continuation, state, PKCE, nonce and signed OIDC identity.
2. Browser application API (`/api/**`): existing JDBC-backed browser sessions or bound bearer identities, never creating a session; the redacted current-invitation lookup is public but reads only an existing session. An interceptor rejects unsafe requests missing the same-origin mutation header.
3. Browser routes: existing invitation intake/continuation and Keycloak OAuth2 Login Authorization Code + PKCE.

Both authentication modes validate provider tokens, then resolve exact `(issuer, subject)` to `ActorId`. Bearer requests with no binding fail `401`. Ordinary browser login requires active Tenant authority unless a pending invitation authorizes admission. Both invitation paths enter the same locked transaction that binds the identity, grants Tenant `MEMBER`, and consumes the invitation before the `ActorId`-only session is persisted. Arconia request context never substitutes for membership or capability authorization.

On successful browser login, Spring Security session-fixation protection rotates the session ID. The callback replaces `OAuth2AuthenticationToken` with an `ActorAuthenticationToken` carrying no credentials, explicitly saves a security context whose serializable principal contains only `ActorId`, and uses a discarding authorized-client repository. Provider access, refresh, and raw ID-token state is not retained in Spring Session.

Google acquisition is independent of Actor login. Its callback never replaces Actor identity or infers a binding from email. The owner app and refresh grant are encrypted with Tenant/credential/key-version context outside Spring Session; consent temporarily binds its encrypted app to owner, Tenant, target and expected authority. Access tokens remain transient. Audience and authorized-party checks use that app, not a global client. Concurrent consent, stale callback, wrong-account reconnect and stale refresh failure remain fenced by owner/session/state and credential/payload revisions. See the [Connector contract](docs/specs/connector.md).

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | Health status |
| `GET /api/identity/me` | Valid bound bearer identity or authenticated browser session | Stable `actorId`, nullable active Tenant presentation context, and capability list |
| `GET /` | Browser origin | Static application; session state is resolved through `/api/identity/me` |
| `GET /access-not-provisioned` | Browser origin | Public accessible denial state |
| `GET /invite/{secret}` | Public capability link | Digest lookup, redacted JDBC continuation, then invitation landing |
| `GET /invite/activate` | Public Keycloak action return | Clear stale continuation, mark activation flow, and start browser OAuth2 login |
| `/api/invitations/**` | Active Tenant owner, except redacted current continuation | Create/list/rotate/revoke lifecycle and recipient landing context |
| `/api/credentials/google-drive/**` | Active Tenant owner | Independent credential catalog, consent, reconnect, revoke and guarded unused-credential deletion |
| `/api/sources/**` | Active Tenant owner | FILE uploads; Google Source creation, selection and sync; shared source/item lifecycle commands |
| `/api/source-operations/**` | Active Tenant owner | Poll durable ingestion, source-sync or cleanup status |

## API error contract

Spring Boot MVC Problem Details is enabled for framework exceptions. Expected capability failures are carried by typed `BusinessException` subclasses and mapped to RFC 9457 by the narrow `ApiExceptionHandler`. The same advice handles only `MethodArgumentNotValidException` and `HandlerMethodValidationException` to publish safe stable field/parameter errors; it does not extend `ResponseEntityExceptionHandler` or catch `Exception`, `IllegalArgumentException`, or persistence exceptions. Diagnostic messages, rejected sensitive values, bytes, extracted text, claim tokens, and parser failures are never exposed.

Browser redirect controllers continue to consume typed exceptions directly, `ACCESS_NOT_PROVISIONED` remains a browser SPA destination, and Spring Security filter-chain failures remain outside MVC advice. Unexpected exceptions are not caught by the global handler.

## Published API contract

The committed root `openapi.yml` is a generated snapshot of the live `/api/**` Spring MVC surface and is the sole input to the committed Hey API client under `web/src/lib/hey-api`. Spring controller annotations and Spring-visible request/response types own operation IDs, status/media metadata, security requirements, and schema constraints; the YAML file is not edited as an independent contract.

Springdoc's WebMVC API starter is present without Swagger UI. API-doc endpoints are disabled in normal runtime configuration and enabled only by `OpenApiContractTest`, which starts the real API context, retrieves the grouped browser document through MockMvc, verifies the exact public path set, and compares it semantically with `openapi.yml`. The Gradle gate rejects backend/snapshot drift; the frontend `check:api` gate rejects snapshot/client drift.

## External identity provider

Keycloak is the fixed browser credential store and enterprise OIDC/SAML broker. MemoryOS owns the lifecycle of the single Keycloak container shared with OrgMemory while each repository owns only its own realm configuration. MemoryOS reconciliation creates or reuses the named initial owner, retains public PKCE client `memoryos-integration`, reconciles confidential `memoryos-web`, `memoryos-mailpit`, `memoryos-pgweb`, `memoryos-redisinsight`, and `memoryos-minio-console`, and creates the realm-local confidential `memoryos-user-provisioner` service account. The three inspection clients expose only the realm-local `memoryos-inspector` role, which is assigned solely to the reconciled initial owner; the MinIO client maps that client-scoped role into the `policy` claim used by MinIO STS. They never expose the master realm or bootstrap administrator. `memoryos-web` retains only the exact Spring callback and exact `/invite/activate` return URI with mandatory S256 PKCE; wildcards remain forbidden. Identity-owned provider integration uses the provisioner to create or reuse invited local accounts and send bounded `VERIFY_EMAIL` execute-actions links; the service account has only realm-local `manage-users`.

## Deployment

The deployment is an explicit overlay contract. `compose.base.yaml` owns PostgreSQL, private MinIO with a durable volume, one-shot bucket/policy/sentinel bootstrap, shared Keycloak, API, worker, and web. MinIO receives distinct least-privilege API and worker identities from mounted secret files; its browser CORS allowlist and the web `connect-src` are configured to exact origins. The API signs against a browser-reachable endpoint but inspects through the internal service endpoint. `compose.staging.yaml` adds Mailpit, TLS Redis, read-only PostgreSQL/Redis inspectors, native MinIO Console OIDC, and file-backed inspection secrets. pgweb and Redis Insight remain behind separate OAuth2 Proxies on loopback ports `18026` and `18027`; MinIO's container-only port `9001` is reached through a dedicated HTTPS proxy host and receives no host binding. `compose.production.yaml` adds production profiles and no inspection exposure or MinIO OIDC configuration. API and worker remain separate image targets; worker starts after API and Redis health, exposes datasource/Redis/db-scheduler/object-storage readiness internally, and runs with bounded resources and shutdown.

The staging application origin is `https://memoryos.72-62-193-33.nip.io`, terminated by Nginx Proxy Manager and forwarded to `memoryos-web:8080`. The object-storage origin routes directly to `memoryos-minio:9000` and must exactly match the configured presigning endpoint, MinIO CORS origin, and web CSP `connect-src`. The separate owner-only Console origin routes to `memoryos-minio:9001`; native OIDC returns only to its exact `/oauth_callback`, and claim-based authorization grants the bucket-read-only `memoryos-inspector` policy only to the initial owner. The confidential `memoryos-web` client retains the matching HTTPS callback, `/invite/activate` action return, root, and web origin with S256 PKCE; staging's secure JDBC-session cookie is therefore exercised over HTTPS rather than a loopback development rewrite.

## Extraction and deferred capabilities

Structured extraction is documented in the [Document contract](docs/specs/document.md) and [Ingestion contract](docs/specs/ingestion.md). The base deployment includes a digest-pinned CPU Docling service on the private network, without host ports or object-storage credentials. Worker publishes checksum-verified canonical artifacts to MinIO and updates the current Document reference transactionally; a separate recurring sweep reclaims unreferenced artifacts. This is extraction, not chunking, embedding or search indexing.

No multi-Tenant switcher, broker policy, audit history, OpenFGA client, Google document ACLs, reader identity linking, document viewer, MCP server, GraphRAG engine, durable memory screen or chat execution exists. Add deferred components only through a capability-owned vertical slice with a verified production path.

## Staging observability

API and worker package shared Logback and Micrometer/OpenTelemetry configuration from
`config/observability`. Staging emits JSON stdout and OTLP HTTP logs, metrics and
traces to an independently managed Collector/Loki/Tempo/Prometheus/Grafana Compose
project. Backends and ingest are private; Grafana uses native Keycloak OIDC with
a strict `memoryos-inspector` role gate and separate local break-glass credentials.
Nullable operation-origin IDs persist across PostgreSQL dispatch and Redis delivery.
Worker publication/processing spans are separate roots with causal links; telemetry
never changes durable claim, fencing or authorization semantics.
The ingestion coordinator records bounded processing outcomes independently of ACK;
connector persistence supplies creation-to-first-claim wait from database timestamps.
See the [processing telemetry contract](docs/specs/ingestion.md#processing-telemetry).

See the [logging policy](docs/guidelines/observability.md),
[verification matrix](docs/tests/observability.md), and
[deployment runbook](infrastructure/observability/README.md). Repository configuration
and local validation are distinct from staging rollout acceptance.
