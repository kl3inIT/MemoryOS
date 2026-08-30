# MemoryOS architecture

This document describes the system implemented in this repository. Product intent lives in [docs/vision.md](docs/vision.md); accepted rationale lives in [ADRs](docs/decisions/).

## System shape

MemoryOS is a controlled Spring Modulith monolith with four flat Gradle modules:

| Module | Runtime role | Dependency rule |
| --- | --- | --- |
| `core` | Complete capability implementations: contracts, transactions, and capability-owned persistence | Must not depend on an adapter or deployable |
| `connector` | Shared provider integration bundle; current `provider.file` adapter carries Tika 4 | Depends only on public `core` APIs |
| `api` | Spring Boot HTTP, validation, migration, and security composition root | Depends on `core`; MEM-35 excludes `connector`/Tika |
| `worker` | PostgreSQL-backed execution and control-plane composition root | Depends on `core`, selects `connector` at runtime, and alone composes Redis/db-scheduler |

`api` and `worker` are separate deployables. API owns Flyway migrations and durable source commands. Worker starts after API health, claims leased index/cleanup work, extracts outside database transactions, and token-guardedly finalizes or reclaims work. The current business executor still polls PostgreSQL directly; Redis Streams carry no business work until the relay and consumer cutover.

`web/` is a separate production deployable built with Vite, React, TanStack Router, TanStack Query, Tailwind CSS, and generated Hey API clients. It is not a Gradle module or a reusable package. The Nginx runtime serves immutable assets and owns the browser origin; Spring remains the API, OAuth2, session, and authorization runtime.

The pathless authenticated route owns `ApplicationSessionBoundary`, which resolves `/api/identity/me` once for all protected child routes and provides one application-session context. The nested administration layout owns capability-specific gates and one persistent administration `AppShell`: invitation surfaces require `INVITATIONS_MANAGE`, Sources surfaces require `SOURCES_MANAGE`, and a denied deep link mounts no protected query. The Sources flow creates FILE sources, uploads one bounded file, polls indexing/cleanup state, and exposes safe item/retry/remove/delete operations without binary or extracted content.

Application-owned links use TanStack Router and preserve the browser document; OAuth2, provider logout, invitation continuation, mail, and fragment navigation remain native. The QueryClient retains an accepted actor/authority fingerprint across route remounts, refetches identity whenever the browser returns to the foreground, and purges non-identity queries plus mutations before accepting changed authority or converging any private `401` to signed-out state. Invitation creation is a semantic single-flight form. Vite emits imported fonts as same-origin assets, and the build rejects inline `data:font` URLs to remain compatible with the production `font-src 'self'` policy.

The web design system remains local to the single application. `styles/tokens.css` owns the monochrome primitives and semantic roles, `styles/theme.css` maps those roles and the Hanken Grotesk type scale into Tailwind v4, and `styles/base.css` owns global element behavior. Product components consume semantic roles and typography presets rather than raw palette or font-size values. Shared `SidebarTab`, `SidebarSection`, and `MenuItem` primitives own navigation/menu interaction. No reusable design-system package, Storybook surface, Style Dictionary pipeline, cross-platform token output, or migration component tree exists without a second consumer.

## Capability boundaries

`core` contains six implemented closed Spring Modulith modules: `identity`, `tenant`, `invitation`, `connector`, `document`, and `ingestion`. A capability root package is its public API; application services own authorization, validation, orchestration, and transaction boundaries, while concrete `persistence` repositories own SQL, row mapping, locks, claims, conditional transitions, and bulk operations.

`tenant` depends only on `identity`. `invitation` depends on public `identity` and `tenant`. `document` depends only on `tenant`. `connector` depends on public `identity`, `tenant`, and `document`; it owns Connector/Credential/Pair/item and Pair/Document provenance. `ingestion` depends on public `connector`, `document`, and `tenant` APIs and owns asynchronous orchestration. Spring Modulith and ArchUnit reject cycles, cross-capability persistence imports, deployable dependencies, and provider imports of capability internals.

`api` scans `io.memoryos`, so capability implementations register through Spring stereotypes. API composes Arconia Web fixed Tenant resolution and verifies the configured UUID against active Tenant persistence. Worker scans worker plus connector/document/ingestion and required Tenant persistence packages without loading API/security or Arconia composition; durable index and cleanup records carry the explicit `TenantId` used by every worker repository predicate. Redis and db-scheduler are worker composition concerns, not capability dependencies.

Audit is intentionally absent until a real evidence consumer defines attribution, transaction, retention, access, and export semantics. See [ADR 0003](docs/decisions/0003-defer-audit-until-evidence-consumer.md).

## Persistence and startup

Flyway owns seven migrations:

- `V1__create_identity_tables.sql`: stable `actors` and exact `(issuer, subject)` bindings.
- `V2__create_initial_organization_and_sessions.sql`: historical Organization/default-Workspace schema and Spring Session JDBC tables.
- `V3__create_organization_invitations.sql`: historical Invitation lifecycle initially scoped by Organization/default Workspace.
- `V4__collapse_workspace_into_organization.sql`: removes the default-Workspace layer and makes Organization the direct historical owner.
- `V5__create_file_source_and_document_schema.sql`: historical Organization-scoped Connector/Credential/Pair/item/attempt/Document/provenance/cleanup state.
- `V6__cut_over_organization_to_tenant.sql`: renames the active schema to Tenant, preserves UUIDs and composite ownership, and enforces one `deployment_slot = 1` Tenant row.
- `V7__create_scheduler_control_plane.sql`: db-scheduler's PostgreSQL control-plane table and execution/heartbeat indexes; it contains no Tenant or business-operation authority.

API startup requires datasource, OIDC, confidential browser-client, and initial Tenant configuration including `MEMORYOS_TENANT_ID`. After migration, an `ApplicationRunner` locks the singleton bootstrap row, resolves or creates the exact owner binding, inserts or verifies the configured Tenant UUID, grants Tenant `OWNER`, and publishes the same UUID. Concurrent replicas serialize on that row. Identical configuration replays; UUID, owner, authority, lifecycle, or descriptive drift fails startup.

The worker composes a two-thread db-scheduler runtime over the shared PostgreSQL database. Its first recurring control task, `memoryos-redis-topology-ensure`, idempotently ensures fixed ingestion and cleanup Redis Streams plus consumer groups. The `scheduled_tasks` row provides cluster-safe ownership, heartbeat, recovery, and success/failure evidence for that bounded task. Redis readiness, datasource readiness, and db-scheduler readiness are all required for worker readiness; API health remains independent of Redis.

Arconia Redis Dev Services supplies isolated development/test Redis only. Arconia PostgreSQL Dev Services is intentionally absent: API and worker are separate processes over one shared authority database, while the PostgreSQL Dev Service has no shared-service discovery contract and would provision isolated databases per application. Development therefore uses one externally managed PostgreSQL datasource shared by both deployables; PostgreSQL integration tests use explicit isolated containers. The production worker requires explicit authenticated Redis connection, TLS, timeout, pool, and unique scheduler-name configuration. Production artifacts exclude Arconia Dev Services and Testcontainers.

The next execution change is one non-separable vertical cutover spanning MEM-43 relay, MEM-44 consumers, and MEM-51 fencing/drain/removal. Those work packages may be tracked separately, but they must share one increment and production delivery: a relay without consumers accumulates unused delivery, consumers beside the current poller risk dual execution, and poller removal is unsafe before both exist.

## Authentication

The API composes three ordered security chains:

1. Browser application API (`/api/identity/me`, `/api/invitations/**`, `/api/sources/**`, and `/api/source-operations/**`): existing JDBC-backed browser sessions or bound bearer identities; the redacted current-invitation lookup is public but reads only an existing session.
2. Remaining `/api/**`: stateless OAuth2 Resource Server bearer authentication.
3. Browser routes: invitation intake/continuation plus OAuth2 Login Authorization Code + PKCE with JDBC-backed sessions.

Both authentication modes validate provider tokens, then resolve exact `(issuer, subject)` to `ActorId`. Bearer requests with no binding fail `401`. Ordinary browser login requires active Tenant authority unless a pending invitation authorizes admission. Both invitation paths enter the same locked transaction that binds the identity, grants Tenant `MEMBER`, and consumes the invitation before the `ActorId`-only session is persisted. Arconia request context never substitutes for membership or capability authorization.

On successful browser login, Spring Security session-fixation protection rotates the session ID. The callback replaces `OAuth2AuthenticationToken` with `ActorSessionAuthenticationToken`, explicitly saves a security context whose serializable principal contains only `ActorId`, and uses a discarding authorized-client repository. Provider access, refresh, and raw ID-token state is not retained in Spring Session.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | Health status |
| `GET /api/identity/me` | Valid bound bearer identity or authenticated browser session | Stable `actorId`, nullable active Tenant presentation context, and capability list |
| `GET /` | Browser origin | Static application; session state is resolved through `/api/identity/me` |
| `GET /access-not-provisioned` | Browser origin | Public accessible denial state |
| `GET /invite/{secret}` | Public capability link | Digest lookup, redacted JDBC continuation, then invitation landing |
| `GET /invite/activate` | Public Keycloak action return | Clear stale continuation, mark activation flow, and start browser OAuth2 login |
| `/api/invitations/**` | Active Tenant owner, except redacted current continuation | Create/list/rotate/revoke lifecycle and recipient landing context |
| `/api/sources/**` | Active Tenant owner | Create/list/detail/upload/reindex/remove/delete FILE source lifecycle; mutations use explicit POST commands |
| `/api/source-operations/**` | Active Tenant owner | Poll durable index or cleanup operation status |

## API error contract

Spring Boot MVC Problem Details is enabled for framework exceptions. Expected capability failures are carried by typed `BusinessException` subclasses and mapped to RFC 9457 by the narrow `ApiExceptionHandler`. The same advice handles only `MethodArgumentNotValidException` and `HandlerMethodValidationException` to publish safe stable field/parameter errors; it does not extend `ResponseEntityExceptionHandler` or catch `Exception`, `IllegalArgumentException`, or persistence exceptions. Diagnostic messages, rejected sensitive values, bytes, extracted text, claim tokens, and parser failures are never exposed.

Browser redirect controllers continue to consume typed exceptions directly, `ACCESS_NOT_PROVISIONED` remains a browser SPA destination, and Spring Security filter-chain failures remain outside MVC advice. Unexpected exceptions are not caught by the global handler.

## Published API contract

The committed root `openapi.yml` is a generated snapshot of the live `/api/**` Spring MVC surface and is the sole input to the committed Hey API client under `web/src/lib/hey-api`. Spring controller annotations and Spring-visible request/response types own operation IDs, status/media metadata, security requirements, and schema constraints; the YAML file is not edited as an independent contract.

Springdoc's WebMVC API starter is present without Swagger UI. API-doc endpoints are disabled in normal runtime configuration and enabled only by `OpenApiContractTest`, which starts the real API context, retrieves the grouped browser document through MockMvc, verifies the exact public path set, and compares it semantically with `openapi.yml`. The Gradle gate rejects backend/snapshot drift; the frontend `check:api` gate rejects snapshot/client drift.

## External identity provider

Keycloak is the fixed browser credential store and enterprise OIDC/SAML broker. MemoryOS owns the lifecycle of the single Keycloak container shared with OrgMemory while each repository owns only its own realm configuration. MemoryOS reconciliation creates or reuses the named initial owner, retains public PKCE client `memoryos-integration`, reconciles confidential `memoryos-web` and `memoryos-mailpit`, and creates the realm-local confidential `memoryos-user-provisioner` service account. `memoryos-web` retains only the exact Spring callback and exact `/invite/activate` return URI with mandatory S256 PKCE; wildcards remain forbidden. Identity-owned provider integration uses the provisioner to create or reuse invited local accounts and send bounded `VERIFY_EMAIL` plus `UPDATE_PASSWORD` action emails. It never contains or executes OrgMemory realm/client/user/scope/mapper provisioning. Operator, browser-client, provisioner, SMTP, and one-time user secrets are read from managed environment or mode-`0600` files and never enter command arguments or output.

## Deployment

The hardened deployment Compose project owns `memoryos-postgres`, `shared-keycloak`, `memoryos-mailpit`, `memoryos-mailpit-oauth2-proxy`, `memoryos-api`, `memoryos-worker`, and `memoryos-web`. API and worker use separate image targets from the layered backend Dockerfile; worker starts only after API health, connects to the separately managed Redis endpoint, exposes datasource/Redis/db-scheduler readiness internally, runs read-only with bounded temporary storage, and has explicit CPU/memory and shutdown limits.

The staging application origin is `https://memoryos.72-62-193-33.nip.io`, terminated by Nginx Proxy Manager and forwarded to `memoryos-web:8080`. The confidential `memoryos-web` client retains the matching HTTPS callback, `/invite/activate` action return, root, and web origin with S256 PKCE; staging's secure JDBC-session cookie is therefore exercised over HTTPS rather than a loopback development rewrite.

## Deferred components

No multi-Tenant switcher, broker policy, audit history, OpenFGA client, Google connector, MCP server, GraphRAG engine, account-linking endpoint, durable memory screen, or chat UI exists. Add every deferred component only through a capability-owned vertical slice with a verified production path.