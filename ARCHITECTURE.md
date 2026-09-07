# MemoryOS architecture

This document describes the system implemented in this repository. Product intent lives in [docs/vision.md](docs/vision.md); accepted rationale lives in [ADRs](docs/decisions/).

## System shape

MemoryOS is a controlled Spring Modulith monolith with four flat Gradle modules:

| Module | Runtime role | Dependency rule |
| --- | --- | --- |
| `core` | Five closed capability implementations: public contracts, transactions, JPA IAM lifecycle, JDBC resource persistence, and the object-storage S3 adapter | Must not depend on `connector` or a deployable |
| `connector` | Shared provider integration bundle; `provider.file` routes PDF/DOCX/PPTX to Docling Serve and text to bounded Tika 4 | Depends only on public `core` APIs |
| `api` | Spring Boot HTTP, validation, migration, and security composition root | Depends on `core`; MEM-35 excludes `connector`/Tika |
| `worker` | PostgreSQL-authoritative Redis Stream execution and control-plane composition root | Depends on `core`, selects `connector` at runtime, and alone composes Redis/db-scheduler |

`api` and `worker` are separate deployables. API owns Flyway migrations and durable source/upload commands. Browser file bytes travel by checksum-bound presigned PUT directly to object storage and never through API request bodies. API transactions commit index/cleanup operations without contacting Redis. Worker db-scheduler relays eligible identifiers from PostgreSQL into workload-specific Redis Streams; fixed consumer-group loops reload and token-claim each authoritative operation, stream stored objects through bounded extraction, renew long processing leases, and finalize durably before manual acknowledgement. PostgreSQL remains business authority; Redis is rebuildable delivery state; MinIO holds immutable FILE binaries.

`web/` is a separate production deployable built with Vite, React, TanStack Router, TanStack Query, Tailwind CSS, and generated Hey API clients. It is not a Gradle module or a reusable package. The Nginx runtime serves immutable assets and owns the browser origin; Spring remains the API, OAuth2, session, and authorization runtime.

The pathless authenticated route owns `ApplicationSessionBoundary`, which resolves `/api/identity/me` for protected child routes and provides one application-session context. The persistent administration `AppShell` gates Users on global `USERS_MANAGE`, Groups on global or scoped `GROUPS_READ`, and Sources on global or scoped `SOURCES_READ`; denied deep links mount no protected query. `/admin/users` presents active/inactive memberships and eligible invitations with bounded search, status/role/Group filters, sorting, pagination, global status counts, real Group tags/overflow, persisted `STANDARD` account classification, and compact actions. `IAM_ADMIN` enables the ordinary-membership editor. `/admin/groups` provides bounded list/create/detail, member/candidate selection, protected manager/grant commands, and authorized Source associations.

`/admin` remains a semantic configured-Source table with provider summaries and only server-visible Sources. FILE setup at `/admin/sources/new/file` is one name/file/Group-selection form, not a wizard. One submit creates, uploads, and finalizes; retries retain the created Source and finalization retries do not resend bytes. `/admin/sources/{sourceId}` shows the Source's allowed actions, upload/indexing state, and Group associations without exposing extracted content. The persistent layout retains only pending finalization's Source/upload identifiers and filename across route navigation, never presigned URLs or bytes. Scoped managers receive upload/reindex controls but no creation, destructive, or association-edit controls.

Application-owned links use TanStack Router and preserve the browser document; OAuth2, provider logout, invitation continuation, mail, and fragment navigation remain native. The QueryClient fingerprints Actor, Tenant role, global/scoped capabilities, and Tenant `authorizationVersion`. On changed authority it resets active query state before removing private queries, so existing observers stop rendering revoked data even when capability tokens remain unchanged; mutation cache state is cleared as well. Foreground identity refresh and private `401`/`403` convergence retain one canonical identity query. Unchanged authority retains private state. `ApplicationSessionProvider` remains keyed by `actorId` for cross-actor local-state isolation; revision changes do not blanket-remount one-time invitation result dialogs. Invitation creation/rotation is single-flight and recovery secrets remain dialog-local until close. Vite emits same-origin fonts and rejects inline font URLs for the production CSP.

The web design system remains local to the single application. `styles/tokens.css` owns the monochrome primitives and semantic roles, `styles/theme.css` maps those roles and the Hanken Grotesk type scale into Tailwind v4, and `styles/base.css` owns global element behavior. Product components consume semantic roles and typography presets rather than raw palette or font-size values. Shared `SidebarTab`, `SidebarSection`, and `MenuItem` primitives own navigation/menu interaction. No reusable design-system package, Storybook surface, Style Dictionary pipeline, cross-platform token output, or migration component tree exists without a second consumer.

## Capability boundaries

`core` contains five implemented closed Spring Modulith modules: `iam`, `objectstorage`, `connector`, `document`, and `ingestion`. IAM combines identity, Tenant membership, invitations, Users, Groups, and authorization. Capability roots expose identifiers and operation/projection contracts, never entities. Application services own authorization, validation, orchestration, and transaction boundaries; concrete `persistence` repositories own JPA lifecycle operations, SQL projections, row mapping, locks, claims, and bulk operations.

`iam` does not depend on another capability. `objectstorage` depends on public `iam`, owns generic object/upload persistence, and contains its S3 adapter without exposing AWS SDK types. `document` depends on public `iam` and `objectstorage`. `connector` depends on public `iam`, `document`, and `objectstorage`; it owns Source/Connector/Credential/Pair/item state, Source–Group associations, provenance, upload receipts, and adopted FILE-object cleanup. `ingestion` depends on public `connector`, `document`, `objectstorage`, and `iam`. No cross-capability JPA relationships exist. Spring Modulith and ArchUnit reject cycles, cross-capability persistence imports, deployable dependencies, and provider imports of capability internals.

`api` scans `io.memoryos` and composes Arconia fixed Tenant resolution. Worker scans its own package plus Connector, Document, Ingestion, Object Storage, and IAM persistence; it explicitly imports only the IAM authorization and Group-scope services needed by Connector. Both roots scan IAM entities and expose a shared `EntityManager` for constructor-injected concrete repositories. They do not load one another's API/security composition. Durable worker records carry the explicit `TenantId` used by repository predicates. Redis and db-scheduler remain worker composition concerns.

Audit is intentionally absent until a real evidence consumer defines attribution, transaction, retention, access, and export semantics. See [ADR 0003](docs/decisions/0003-defer-audit-until-evidence-consumer.md).

## Persistence and startup

Flyway owns sixteen migrations:

- `V1__create_identity_tables.sql`: stable `actors` and exact `(issuer, subject)` bindings.
- `V2__create_initial_organization_and_sessions.sql`: historical Organization/default-Workspace schema and Spring Session JDBC tables.
- `V3__create_organization_invitations.sql`: historical Invitation lifecycle initially scoped by Organization/default Workspace.
- `V4__collapse_workspace_into_organization.sql`: removes the default-Workspace layer and makes Organization the direct historical owner.
- `V5__create_file_source_and_document_schema.sql`: historical Organization-scoped Connector/Credential/Pair/item/attempt/Document/provenance/cleanup state.
- `V6__cut_over_organization_to_tenant.sql`: renames the active schema to Tenant, preserves UUIDs and composite ownership, and enforces one `deployment_slot = 1` Tenant row.
- `V7__create_scheduler_control_plane.sql`: db-scheduler's PostgreSQL control-plane table and execution/heartbeat indexes; it contains no Tenant or business-operation authority.
- `V8__cut_over_operations_to_redis_streams.sql`: adds stable delivery identity, dispatch claims/evidence, rediscovery timing, transport diagnostics, and processing-attempt counters to index and cleanup operations; it removes direct-poller claim indexes.
- `V9__cut_over_file_content_to_object_storage.sql`: creates generic stored-object/upload state and Connector-owned Source upload receipts, removes FILE `BYTEA`, and makes each item version own one restrictive `StoredObject` reference.
- `V10__persist_operation_trace_origins.sql`: persists operation trace origins.
- `V11__add_document_extraction_artifacts.sql`: stores structured extraction artifacts.
- `V12__use_current_documents.sql`: publishes the current Document representation.
- `V13__create_actor_profiles.sql`: stores the latest nullable display-name/email observation for an admitted Actor with exact binding provenance and no provider token state.
- `V14__consolidate_iam_account_types.sql`: persists `STANDARD` Actor classification and Tenant authorization revision, and invalidates pre-cutover serialized Spring Sessions.
- `V15__create_iam_groups.sql`: Tenant-qualified Groups, explicit memberships/manager flags and capability grants; seeds protected Admin/Basic Groups and enforces system-grant constraints.
- `V16__create_source_group_grants.sql`: Tenant-qualified Source–Group associations, seeded to Admin for existing Sources.

IAM lifecycle entities and relationships remain inside `io.memoryos.iam.persistence`. Both composition roots deliberately use JPA transaction management on the same DataSource as JDBC work, with Hibernate `validate`, open-in-view disabled, and ORM caches disabled. Flyway is the only DDL owner. IAM projections/locks and Source, Document, Object Storage, and Ingestion persistence remain JDBC-first. See [ADR 0007](docs/decisions/0007-unified-jpa-iam-and-group-authorization.md).

API startup requires datasource, OIDC, confidential browser-client, object-storage, and initial Tenant configuration including `MEMORYOS_TENANT_ID`. Its transaction locks the singleton bootstrap row, resolves or creates the exact owner binding, inserts or verifies the Tenant UUID, grants Tenant `OWNER`, and idempotently provisions Admin/Basic membership. Concurrent replicas serialize; configuration, owner, authority, lifecycle, or descriptive drift fails startup. V14 requires a coordinated API/worker cutover and fresh browser login rather than old-package session aliases.

The worker composes a two-thread db-scheduler control plane over the shared PostgreSQL database. `memoryos-redis-execution-topology-reconcile-v1` idempotently ensures the versioned ingestion and cleanup Redis Streams plus consumer groups. A separate bounded task cancels index work owned by inactive Tenants without lengthening relay claim transactions; `memoryos-abandoned-object-upload-cleanup-v1` token-claims and deletes expired pre-adoption objects. Recurring ingestion and cleanup relay tasks claim bounded dispatch batches and publish identifier-only messages. The `scheduled_tasks` rows provide cluster-safe ownership, heartbeat, recovery, and success/failure evidence for those control tasks. Redis, datasource, db-scheduler, and object-storage sentinel readiness are all required for worker readiness. API health also requires the private sentinel but remains independent of Redis.

Both deployables run on Java 25 with Spring Boot virtual threads enabled and `spring.main.keep-alive=true`. Spring-managed request, asynchronous-task, and scheduling execution uses virtual threads. db-scheduler uses a named virtual thread per control-task execution while its configured `threads = 2` bounds concurrent control work. The worker owns one long-lived consumer loop per workload and a virtual thread per claimed delivery; workload batch sizes plus database and Redis connection pools bound downstream concurrency. Long-lived db-scheduler polling/housekeeping, Lettuce/Netty event loops, and datasource housekeeping remain library-managed platform threads.

Arconia's bootstrap profile is `development` for both deployables. The development API alone owns PostgreSQL Dev Services on fixed host port `55432`; the worker connects to that shared authority database and alone owns Redis Dev Services on fixed host port `56379`. Tests retain isolated containers where exact migration or multi-instance control matters, and no cross-application Testcontainers reuse contract exists. Arconia's fixed-port API delegates host binding to Testcontainers, which currently publishes on Docker's host interfaces rather than a configurable loopback address; these services are development-only and require the developer host firewall on non-private networks. Production artifacts exclude Arconia Dev Services and Testcontainers.

Execution has one cut-over path: PostgreSQL operation → db-scheduler relay → workload Redis Stream/group → identifier-scoped PostgreSQL claim and lease → durable terminal transition → XACK/XDEL. Dispatch evidence rediscoveries rebuild nonterminal work after Redis loss; a message is reclaimed only when Redis idle time and PostgreSQL lease state both permit it. No direct PostgreSQL business poller, alternate dispatcher, execution-mode switch, or compatibility path remains.

## Authentication

The API composes two ordered security chains:

1. Browser application API (`/api/**`): existing JDBC-backed browser sessions or bound bearer identities, never creating a session; the redacted current-invitation lookup is public but reads only an existing session. A Spring MVC interceptor additionally rejects every unsafe `/api/**` request that lacks the same-origin mutation header.
2. Browser routes: invitation intake/continuation plus OAuth2 Login Authorization Code + PKCE with JDBC-backed sessions.

Both authentication modes validate provider tokens and resolve exact `(issuer, subject)` to `ActorId`. Unbound bearer identities fail `401`; ordinary authentication does not create authority. Eligible invitation admission atomically binds the Actor, grants active Tenant `MEMBER`, assigns Basic without manager/Admin elevation, and consumes the invitation before saving the ActorId-only session. Every protected operation resolves current membership and applicable IAM authority; deactivation denies the next request, while `/api/identity/me` returns the bound Actor with `tenant: null`. Its repeatable-read projection includes expanded global capabilities, eligible scoped capabilities, and the Tenant revision. Role presentation and Arconia context never substitute for authorization.

Explicit Group grants form a union with one implication graph. `IAM_ADMIN` is reserved to Admin; Basic grants no administrative capabilities. Ordinary-Group managers have only eligible scoped operations, cannot mutate manager status by deleting a manager membership, and cannot exceed delegation limits. IAM mutations hold the Tenant row exclusively and increment its revision; protected Source writes hold a shared Tenant lock and reauthorize their concrete SQL scope before commit. Provider IO stays outside these locks. The [IAM contract](docs/specs/identity.md) and [Source contract](docs/specs/connector.md) own the detailed permission matrices.

On successful browser login, Spring Security session-fixation protection rotates the session ID. After authority admission and before persisting the application session, the callback records the latest nullable display-name/email and verification observation against the admitted Actor's exact binding. It then replaces `OAuth2AuthenticationToken` with an `ActorAuthenticationToken` carrying no credentials, explicitly saves a security context whose serializable principal contains only `ActorId`, and uses a discarding authorized-client repository. Provider access, refresh, and raw ID-token state is not retained in Spring Session or profile persistence.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | Health status |
| `GET /api/identity/me` | Valid bound bearer identity or browser session | Actor, nullable Tenant, global/scoped capabilities, and authorization revision |
| `GET /` | Browser origin | Static application; session state is resolved through `/api/identity/me` |
| `GET /access-not-provisioned` | Browser origin | Public accessible denial state |
| `GET /invite/{secret}` | Public capability link | Digest lookup, redacted JDBC continuation, then invitation landing |
| `GET /invite/activate` | Public Keycloak action return | Clear stale continuation, mark activation flow, and start browser OAuth2 login |
| `/api/invitations/**` | Global `USERS_MANAGE`, except redacted current continuation | Issue/list/rotate/revoke and recipient landing context |
| `GET /api/users` | Global `USERS_MANAGE` | Bounded membership/invitation directory with profiles, account classification, and Groups |
| `POST /api/users/{actorId}/activate` | Global `USERS_MANAGE` | Idempotent existing-member activation; configured owner protected |
| `POST /api/users/{actorId}/deactivate` | Global `USERS_MANAGE` | Idempotent deactivation preserving history; owner and final-active-admin guards |
| `POST /api/users/{actorId}/groups` | `IAM_ADMIN` | Replace ordinary memberships, preserving system edges and retained manager flags |
| `/api/groups/**` | Applicable global IAM capability or managed-Group scope | Authorized Group/member projections and explicit lifecycle/manager/grant commands |
| `/api/sources/**` | Applicable global Source capability or associated managed-Group scope | Filtered reads and scoped upload/reindex; create, association, remove, and delete commands require global authority |
| `/api/source-operations/**` | Global `SOURCES_READ` or associated managed-Group scope | Poll only authorized durable operations |

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

## Deferred components

Structured extraction is documented in the [Document contract](docs/specs/document.md) and [Ingestion contract](docs/specs/ingestion.md). The base deployment includes a digest-pinned CPU Docling service on the private network, without host ports or object-storage credentials. Worker publishes checksum-verified canonical artifacts to MinIO and updates the current Document reference transactionally; a separate recurring sweep reclaims unreferenced artifacts. This is extraction, not chunking, embedding or search indexing.

No multi-Tenant switcher, broker policy, audit history, non-`STANDARD` account-creation/credential flow, SCIM/Requests surface, OpenFGA client, Google connector, MCP server, GraphRAG engine, account-linking endpoint, durable memory screen, or chat runtime exists. Add deferred components only through capability-owned vertical slices with verified production paths.

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
