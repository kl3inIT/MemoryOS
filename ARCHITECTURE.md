# MemoryOS architecture

This document describes the system implemented in this repository. Product intent lives in [docs/vision.md](docs/vision.md); accepted rationale lives in [ADRs](docs/decisions/).

## System shape

MemoryOS is a controlled Spring Modulith monolith with four flat Gradle modules:

| Module | Runtime role | Dependency rule |
| --- | --- | --- |
| `core` | Seven closed capability implementations: contracts, transactions, capability-owned persistence, and the object-storage S3 adapter | Must not depend on `connector` or a deployable |
| `connector` | Shared provider integration bundle; current `provider.file` adapter carries Tika 4 | Depends only on public `core` APIs |
| `api` | Spring Boot HTTP, validation, migration, and security composition root | Depends on `core`; MEM-35 excludes `connector`/Tika |
| `worker` | PostgreSQL-authoritative Redis Stream execution and control-plane composition root | Depends on `core`, selects `connector` at runtime, and alone composes Redis/db-scheduler |

`api` and `worker` are separate deployables. API owns Flyway migrations and durable source/upload commands. Browser file bytes travel by checksum-bound presigned PUT directly to object storage and never through API request bodies. API transactions commit index/cleanup operations without contacting Redis. Worker db-scheduler relays eligible identifiers from PostgreSQL into workload-specific Redis Streams; fixed consumer-group loops reload and token-claim each authoritative operation, stream stored objects through bounded extraction, renew long processing leases, and finalize durably before manual acknowledgement. PostgreSQL remains business authority; Redis is rebuildable delivery state; MinIO holds immutable FILE binaries.

`web/` is a separate production deployable built with Vite, React, TanStack Router, TanStack Query, Tailwind CSS, and generated Hey API clients. It is not a Gradle module or a reusable package. The Nginx runtime serves immutable assets and owns the browser origin; Spring remains the API, OAuth2, session, and authorization runtime.

The pathless authenticated route owns `ApplicationSessionBoundary`, which resolves `/api/identity/me` once for all protected child routes and provides one application-session context. The nested administration layout owns capability-specific gates and one persistent administration `AppShell`: invitation surfaces require `INVITATIONS_MANAGE`, Sources surfaces require `SOURCES_MANAGE`, and a denied deep link mounts no protected query. `/admin` lists configured Source instances and owns their detail, upload, indexing, and cleanup operations. `/admin/sources/new` lists only implemented Source types; a typed provider-owned wizard renders the selected setup flow, and FILE currently supplies one configuration step at `/admin/sources/new/file`. Successful creation returns to URL-owned `/admin?sourceId={createdSourceId}` selection. The FILE flow computes SHA-256, initiates a bound upload, reports direct object-storage PUT progress/cancellation, retries finalization without re-uploading, polls indexing/cleanup state, and exposes safe item/retry/remove/delete operations without extracted content.

Application-owned links use TanStack Router and preserve the browser document; OAuth2, provider logout, invitation continuation, mail, and fragment navigation remain native. The QueryClient retains an accepted actor/authority fingerprint across route remounts, refetches identity whenever the browser returns to the foreground, and purges non-identity queries plus mutations before accepting changed authority or converging any private `401` to signed-out state. Invitation creation is a semantic single-flight form. Vite emits imported fonts as same-origin assets, and the build rejects inline `data:font` URLs to remain compatible with the production `font-src 'self'` policy.

The web design system remains local to the single application. `styles/tokens.css` owns the monochrome primitives and semantic roles, `styles/theme.css` maps those roles and the Hanken Grotesk type scale into Tailwind v4, and `styles/base.css` owns global element behavior. Product components consume semantic roles and typography presets rather than raw palette or font-size values. Shared `SidebarTab`, `SidebarSection`, and `MenuItem` primitives own navigation/menu interaction. No reusable design-system package, Storybook surface, Style Dictionary pipeline, cross-platform token output, or migration component tree exists without a second consumer.

## Capability boundaries

`core` contains seven implemented closed Spring Modulith modules: `identity`, `tenant`, `invitation`, `objectstorage`, `connector`, `document`, and `ingestion`. A capability root package is its public API; application services own authorization, validation, orchestration, and transaction boundaries, while concrete `persistence` repositories own SQL, row mapping, locks, claims, conditional transitions, and bulk operations.

`tenant` depends only on `identity`. `invitation` depends on public `identity` and `tenant`. `objectstorage` depends on `tenant`, owns generic object/upload persistence, and contains its concrete S3 adapter without exposing AWS SDK types. `document` depends only on `tenant`. `connector` depends on public `identity`, `tenant`, `document`, and `objectstorage`; it owns Connector/Credential/Pair/item, Pair/Document provenance, Source upload receipts, and adopted FILE-object cleanup. `ingestion` depends on public `connector`, `document`, `objectstorage`, and `tenant` APIs and owns asynchronous orchestration. Spring Modulith and ArchUnit reject cycles, cross-capability persistence imports, deployable dependencies, and provider imports of capability internals.

`api` scans `io.memoryos`, so capability implementations register through Spring stereotypes. API composes Arconia Web fixed Tenant resolution and verifies the configured UUID against active Tenant persistence. Worker scans worker plus Connector, Document, Ingestion, Object Storage, and required Tenant persistence packages without loading API/security or Arconia composition; durable index and cleanup records carry the explicit `TenantId` used by every worker repository predicate. Redis and db-scheduler are worker composition concerns, not capability dependencies.

Audit is intentionally absent until a real evidence consumer defines attribution, transaction, retention, access, and export semantics. See [ADR 0003](docs/decisions/0003-defer-audit-until-evidence-consumer.md).

## Persistence and startup

Flyway owns nine migrations:

- `V1__create_identity_tables.sql`: stable `actors` and exact `(issuer, subject)` bindings.
- `V2__create_initial_organization_and_sessions.sql`: historical Organization/default-Workspace schema and Spring Session JDBC tables.
- `V3__create_organization_invitations.sql`: historical Invitation lifecycle initially scoped by Organization/default Workspace.
- `V4__collapse_workspace_into_organization.sql`: removes the default-Workspace layer and makes Organization the direct historical owner.
- `V5__create_file_source_and_document_schema.sql`: historical Organization-scoped Connector/Credential/Pair/item/attempt/Document/provenance/cleanup state.
- `V6__cut_over_organization_to_tenant.sql`: renames the active schema to Tenant, preserves UUIDs and composite ownership, and enforces one `deployment_slot = 1` Tenant row.
- `V7__create_scheduler_control_plane.sql`: db-scheduler's PostgreSQL control-plane table and execution/heartbeat indexes; it contains no Tenant or business-operation authority.
- `V8__cut_over_operations_to_redis_streams.sql`: adds stable delivery identity, dispatch claims/evidence, rediscovery timing, transport diagnostics, and processing-attempt counters to index and cleanup operations; it removes direct-poller claim indexes.
- `V9__cut_over_file_content_to_object_storage.sql`: creates generic stored-object/upload state and Connector-owned Source upload receipts, removes FILE `BYTEA`, and makes each item version own one restrictive `StoredObject` reference.

API startup requires datasource, OIDC, confidential browser-client, object-storage, and initial Tenant configuration including `MEMORYOS_TENANT_ID`. After migration, an `ApplicationRunner` locks the singleton bootstrap row, resolves or creates the exact owner binding, inserts or verifies the configured Tenant UUID, grants Tenant `OWNER`, and publishes the same UUID. Concurrent replicas serialize on that row. Identical configuration replays; UUID, owner, authority, lifecycle, or descriptive drift fails startup.

The worker composes a two-thread db-scheduler control plane over the shared PostgreSQL database. `memoryos-redis-execution-topology-reconcile-v1` idempotently ensures the versioned ingestion and cleanup Redis Streams plus consumer groups. A separate bounded task cancels index work owned by inactive Tenants without lengthening relay claim transactions; `memoryos-abandoned-object-upload-cleanup-v1` token-claims and deletes expired pre-adoption objects. Recurring ingestion and cleanup relay tasks claim bounded dispatch batches and publish identifier-only messages. The `scheduled_tasks` rows provide cluster-safe ownership, heartbeat, recovery, and success/failure evidence for those control tasks. Redis, datasource, db-scheduler, and object-storage sentinel readiness are all required for worker readiness. API health also requires the private sentinel but remains independent of Redis.

Both deployables run on Java 25 with Spring Boot virtual threads enabled and `spring.main.keep-alive=true`. Spring-managed request, asynchronous-task, and scheduling execution uses virtual threads. db-scheduler uses a named virtual thread per control-task execution while its configured `threads = 2` bounds concurrent control work. The worker owns one long-lived consumer loop per workload and a virtual thread per claimed delivery; workload batch sizes plus database and Redis connection pools bound downstream concurrency. Long-lived db-scheduler polling/housekeeping, Lettuce/Netty event loops, and datasource housekeeping remain library-managed platform threads.

Arconia's bootstrap profile is `development` for both deployables. The development API alone owns PostgreSQL Dev Services on fixed host port `55432`; the worker connects to that shared authority database and alone owns Redis Dev Services on fixed host port `56379`. Tests retain isolated containers where exact migration or multi-instance control matters, and no cross-application Testcontainers reuse contract exists. Arconia's fixed-port API delegates host binding to Testcontainers, which currently publishes on Docker's host interfaces rather than a configurable loopback address; these services are development-only and require the developer host firewall on non-private networks. Production artifacts exclude Arconia Dev Services and Testcontainers.

Execution has one cut-over path: PostgreSQL operation → db-scheduler relay → workload Redis Stream/group → identifier-scoped PostgreSQL claim and lease → durable terminal transition → XACK/XDEL. Dispatch evidence rediscoveries rebuild nonterminal work after Redis loss; a message is reclaimed only when Redis idle time and PostgreSQL lease state both permit it. No direct PostgreSQL business poller, alternate dispatcher, execution-mode switch, or compatibility path remains.

## Authentication

The API composes two ordered security chains:

1. Browser application API (`/api/**`): existing JDBC-backed browser sessions or bound bearer identities, never creating a session; the redacted current-invitation lookup is public but reads only an existing session. A Spring MVC interceptor additionally rejects every unsafe `/api/**` request that lacks the same-origin mutation header.
2. Browser routes: invitation intake/continuation plus OAuth2 Login Authorization Code + PKCE with JDBC-backed sessions.

Both authentication modes validate provider tokens, then resolve exact `(issuer, subject)` to `ActorId`. Bearer requests with no binding fail `401`. Ordinary browser login requires active Tenant authority unless a pending invitation authorizes admission. Both invitation paths enter the same locked transaction that binds the identity, grants Tenant `MEMBER`, and consumes the invitation before the `ActorId`-only session is persisted. Arconia request context never substitutes for membership or capability authorization.

On successful browser login, Spring Security session-fixation protection rotates the session ID. The callback replaces `OAuth2AuthenticationToken` with an `ActorAuthenticationToken` carrying no credentials, explicitly saves a security context whose serializable principal contains only `ActorId`, and uses a discarding authorized-client repository. Provider access, refresh, and raw ID-token state is not retained in Spring Session.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | Health status |
| `GET /api/identity/me` | Valid bound bearer identity or authenticated browser session | Stable `actorId`, nullable active Tenant presentation context, and capability list |
| `GET /` | Browser origin | Static application; session state is resolved through `/api/identity/me` |
| `GET /access-not-provisioned` | Browser origin | Public accessible denial state |
| `GET /invite/{secret}` | Public capability link | Digest lookup, redacted JDBC continuation, then invitation landing |
| `GET /invite/activate` | Public Keycloak action return | Clear stale continuation, mark activation flow, and start browser OAuth2 login |
| `/api/invitations/**` | Active Tenant owner, except redacted current continuation | Create/list/rotate/revoke lifecycle and recipient landing context |
| `/api/sources/**` | Active Tenant owner | Create/list/detail, initiate/finalize direct FILE upload, reindex, remove, and delete lifecycle; mutations use explicit POST commands |
| `/api/source-operations/**` | Active Tenant owner | Poll durable index or cleanup operation status |

## API error contract

Spring Boot MVC Problem Details is enabled for framework exceptions. Expected capability failures are carried by typed `BusinessException` subclasses and mapped to RFC 9457 by the narrow `ApiExceptionHandler`. The same advice handles only `MethodArgumentNotValidException` and `HandlerMethodValidationException` to publish safe stable field/parameter errors; it does not extend `ResponseEntityExceptionHandler` or catch `Exception`, `IllegalArgumentException`, or persistence exceptions. Diagnostic messages, rejected sensitive values, bytes, extracted text, claim tokens, and parser failures are never exposed.

Browser redirect controllers continue to consume typed exceptions directly, `ACCESS_NOT_PROVISIONED` remains a browser SPA destination, and Spring Security filter-chain failures remain outside MVC advice. Unexpected exceptions are not caught by the global handler.

## Published API contract

The committed root `openapi.yml` is a generated snapshot of the live `/api/**` Spring MVC surface and is the sole input to the committed Hey API client under `web/src/lib/hey-api`. Spring controller annotations and Spring-visible request/response types own operation IDs, status/media metadata, security requirements, and schema constraints; the YAML file is not edited as an independent contract.

Springdoc's WebMVC API starter is present without Swagger UI. API-doc endpoints are disabled in normal runtime configuration and enabled only by `OpenApiContractTest`, which starts the real API context, retrieves the grouped browser document through MockMvc, verifies the exact public path set, and compares it semantically with `openapi.yml`. The Gradle gate rejects backend/snapshot drift; the frontend `check:api` gate rejects snapshot/client drift.

## External identity provider

Keycloak is the fixed browser credential store and enterprise OIDC/SAML broker. MemoryOS owns the lifecycle of the single Keycloak container shared with OrgMemory while each repository owns only its own realm configuration. MemoryOS reconciliation creates or reuses the named initial owner, retains public PKCE client `memoryos-integration`, reconciles confidential `memoryos-web`, `memoryos-mailpit`, `memoryos-pgweb`, and `memoryos-redisinsight`, and creates the realm-local confidential `memoryos-user-provisioner` service account. The two inspection clients expose only the realm-local `memoryos-inspector` role, which is assigned solely to the reconciled initial owner; they never expose the master realm or bootstrap administrator. `memoryos-web` retains only the exact Spring callback and exact `/invite/activate` return URI with mandatory S256 PKCE; wildcards remain forbidden. Identity-owned provider integration uses the provisioner to create or reuse invited local accounts and send bounded `VERIFY_EMAIL` execute-actions links; the service account has only realm-local `manage-users`.

## Deployment

The deployment is an explicit overlay contract. `compose.base.yaml` owns PostgreSQL, private MinIO with a durable volume, one-shot bucket/policy/sentinel bootstrap, shared Keycloak, API, worker, and web. MinIO receives distinct least-privilege API and worker identities from mounted secret files; its browser CORS allowlist and the web `connect-src` are configured to exact origins. The API signs against a browser-reachable endpoint but inspects through the internal service endpoint. `compose.staging.yaml` adds Mailpit, TLS Redis, read-only PostgreSQL/Redis inspector bootstrap, pgweb, Redis Insight, and separate OAuth2 Proxies; only the proxies join the shared proxy network and bind operator loopback ports `18026` and `18027`. `compose.production.yaml` adds production profiles and no inspection services. API and worker remain separate image targets; worker starts after API and Redis health, exposes datasource/Redis/db-scheduler/object-storage readiness internally, and runs with bounded resources and shutdown.

The staging application origin is `https://memoryos.72-62-193-33.nip.io`, terminated by Nginx Proxy Manager and forwarded to `memoryos-web:8080`. The object-storage origin is routed directly to `memoryos-minio:9000` on the proxy network and must exactly match the configured presigning endpoint, MinIO CORS origin, and web CSP `connect-src`. The confidential `memoryos-web` client retains the matching HTTPS callback, `/invite/activate` action return, root, and web origin with S256 PKCE; staging's secure JDBC-session cookie is therefore exercised over HTTPS rather than a loopback development rewrite.

## Deferred components

No multi-Tenant switcher, broker policy, audit history, OpenFGA client, Google connector, MCP server, GraphRAG engine, account-linking endpoint, durable memory screen, or chat UI exists. Add every deferred component only through a capability-owned vertical slice with a verified production path.