# MemoryOS architecture

This document describes the system implemented in this repository. Product intent lives in [docs/vision.md](docs/vision.md); accepted rationale lives in [ADRs](docs/decisions/).

## System shape

MemoryOS is a controlled Spring Modulith monolith with three flat Gradle modules:

| Module | Runtime role | Dependency rule |
| --- | --- | --- |
| `core` | Complete capability implementations: contracts, transactions, and capability-owned persistence | Must not depend on a deployable |
| `api` | Spring Boot HTTP and security composition root | Depends on `core` |
| `worker` | Spring Boot background-processing composition root | Depends on `core` |

`api` and `worker` are separate deployables. The worker currently starts and exits because no durable job processor exists.

`web/` is a separate production deployable built with Vite, React, TanStack Router, TanStack Query, Tailwind CSS, and generated Hey API clients. It is not a Gradle module or a reusable package. The Nginx runtime serves immutable assets and owns the browser origin; Spring remains the API, OAuth2, session, and authorization runtime.

The pathless authenticated route owns `ApplicationSessionBoundary`, which resolves `/api/identity/me` once for all protected child routes and provides one application-session context. The nested administration layout owns the capability gate and one persistent administration `AppShell`, so Sources/Invitations navigation preserves shell state and never mounts a denied child. Desktop provides a 15rem sidebar that folds to a 4rem rail; mobile uses a controlled accessible drawer that closes after internal navigation. Organization display, initials, and owner/member labels come from the durable projection. `Admin Panel`, administration routes, and invitation UI require `INVITATIONS_MANAGE`; a denied deep link preserves its URL and mounts no invitation query. Sign-out uses a same-origin guarded POST, invalidates the JDBC application session, and returns a Keycloak RP-initiated logout location so the browser can terminate SSO before returning to the signed-out application.

Application-owned links use TanStack Router and preserve the browser document; OAuth2, provider logout, invitation continuation, mail, and fragment navigation remain native. The QueryClient retains an accepted actor/authority fingerprint across route remounts, refetches identity whenever the browser returns to the foreground, and purges non-identity queries plus mutations before accepting changed authority or converging any private `401` to signed-out state. Invitation creation is a semantic single-flight form. Vite emits imported fonts as same-origin assets, and the build rejects inline `data:font` URLs to remain compatible with the production `font-src 'self'` policy.

The web design system remains local to the single application. `styles/tokens.css` owns the monochrome primitives and semantic roles, `styles/theme.css` maps those roles and the Hanken Grotesk type scale into Tailwind v4, and `styles/base.css` owns global element behavior. Product components consume semantic roles and typography presets rather than raw palette or font-size values. Shared `SidebarTab`, `SidebarSection`, and `MenuItem` primitives own navigation/menu interaction. No reusable design-system package, Storybook surface, Style Dictionary pipeline, cross-platform token output, or migration component tree exists without a second consumer.

## Capability boundaries

`core` contains eight closed Spring Modulith modules: `identity`, `organization`, `invitation`, `authorization`, `knowledge`, `ingestion`, `retrieval`, and `assistant`. A capability root package is its public API. Public enums and records are top-level types. Transactional orchestration lives in `application`; SQL, row mapping, and conditional storage operations live in `persistence`. Capability-owned implementation packages cannot be imported by another capability.

`organization` depends only on `identity`. `invitation` depends only on the public APIs of `identity` and `organization`; it owns invitation lifecycle/persistence and coordinates their mandatory transaction ports without importing either capability's persistence. The remaining dependency graph is enforced by Spring Modulith and ArchUnit tests. `core` may use Spring, `JdbcClient`, transactions, or JPA inside a capability boundary; it is not a framework-free domain layer.

`api` scans `io.memoryos`, so capability implementations in `core` register themselves with Spring stereotypes; deployable configuration defines only infrastructure, security, properties, and startup runners. `worker` scans only `io.memoryos.worker` and excludes datasource auto-configuration until it owns a persistence-backed background runtime path, preventing the shared core JDBC starter from requiring unused worker datasource configuration. Static persistence factories and forwarding `@Bean` methods are intentionally absent.

Audit is intentionally absent until a real evidence consumer defines attribution, transaction, retention, access, and export semantics. See [ADR 0003](docs/decisions/0003-defer-audit-until-evidence-consumer.md).

## Persistence and startup

Flyway owns four migrations:

- `V1__create_identity_tables.sql`: stable `actors` and exact `(issuer, subject)` bindings.
- `V2__create_initial_organization_and_sessions.sql`: historical Organization/default-Workspace schema and Spring Session JDBC tables.
- `V3__create_organization_invitations.sql`: historical Invitation lifecycle initially scoped by Organization/default Workspace.
- `V4__collapse_workspace_into_organization.sql`: removes the default-Workspace layer and makes Organization the direct membership and invitation owner.

API startup requires datasource, OIDC, confidential browser-client, and initial Organization configuration. After migration, an `ApplicationRunner` invokes the transactional initial bootstrap. A migration-created singleton row is locked with `SELECT ... FOR UPDATE`; the transaction resolves or creates the exact owner binding, inserts one Organization, grants Organization `OWNER`, and publishes the Organization ID. Concurrent replicas serialize on that row. Identical configuration replays; drift or incomplete state fails startup.

## Authentication

The API composes three ordered security chains:

1. Browser application API (`/api/identity/me` and `/api/invitations/**`): existing JDBC-backed browser sessions or bound bearer identities; the redacted current-invitation lookup is public but reads only an existing session.
2. Remaining `/api/**`: stateless OAuth2 Resource Server bearer authentication.
3. Browser routes: invitation intake/continuation plus OAuth2 Login Authorization Code + PKCE with JDBC-backed sessions.

Both authentication modes validate provider tokens, then resolve exact `(issuer, subject)` to `ActorId`. Bearer requests with no binding fail `401`. Ordinary browser login additionally requires active Organization authority and otherwise redirects to `ACCESS_NOT_PROVISIONED`. A valid invitation continuation is the only alternate browser path: the Invitation transaction binds the exact identity, grants Organization `MEMBER`, and consumes the invitation before the same `ActorId`-only session is saved. Failure invalidates the partial session. Remaining API routes stay stateless and bearer-only.

On successful browser login, Spring Security session-fixation protection rotates the session ID. The callback replaces `OAuth2AuthenticationToken` with `ActorSessionAuthenticationToken`, explicitly saves a security context whose serializable principal contains only `ActorId`, and uses a discarding authorized-client repository. Provider access, refresh, and raw ID-token state is not retained in Spring Session.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | Health status |
| `GET /api/identity/me` | Valid bound bearer identity or authenticated browser session | Stable `actorId`, nullable active Organization presentation context, and capability list |
| `GET /` | Browser origin | Static application; session state is resolved through `/api/identity/me` |
| `GET /access-not-provisioned` | Browser origin | Public accessible denial state |
| `GET /invite/{secret}` | Public capability link | Digest lookup, redacted JDBC continuation, then invitation landing |
| `/api/invitations/**` | Active Organization owner, except redacted current continuation | Create/list/rotate/revoke lifecycle and recipient landing context |

## API error contract

Spring Boot MVC Problem Details is enabled for framework exceptions. Expected capability failures are carried by typed subclasses of the root-package abstract `BusinessException`, which snapshots a capability-prefixed code, semantic category, and safe fallback message while keeping typed reasons in the capability. Root placement keeps the shared vocabulary outside the eight Spring Modulith capability modules. `ApiExceptionHandler` handles `BusinessException` only for `@RestController` types, maps semantic failure category to HTTP status/title once, and returns RFC 9457 `application/problem+json` with a derived `urn:memoryos:failure:*` type. Diagnostic exception messages are never exposed.

Browser redirect controllers continue to consume typed exceptions directly, `ACCESS_NOT_PROVISIONED` remains a browser SPA destination, and Spring Security filter-chain failures remain outside MVC advice. Unexpected exceptions are not caught by the global handler.

## Published API contract

The committed root `openapi.yml` is a generated snapshot of the live `/api/**` Spring MVC surface and is the sole input to the committed Hey API client under `web/src/lib/hey-api`. Spring controller annotations and Spring-visible request/response types own operation IDs, status/media metadata, security requirements, and schema constraints; the YAML file is not edited as an independent contract.

Springdoc's WebMVC API starter is present without Swagger UI. API-doc endpoints are disabled in normal runtime configuration and enabled only by `OpenApiContractTest`, which starts the real API context, retrieves the grouped browser document through MockMvc, verifies the exact public path set, and compares it semantically with `openapi.yml`. The Gradle gate rejects backend/snapshot drift; the frontend `check:api` gate rejects snapshot/client drift.

## External identity provider

Keycloak is the browser credential store and OIDC provider. MemoryOS owns the lifecycle of the single Keycloak container shared with OrgMemory while each repository owns only its own realm configuration. MemoryOS reconciliation creates or reuses the named initial owner, retains public PKCE client `memoryos-integration`, and reconciles confidential `memoryos-web` and `memoryos-mailpit` clients with Authorization Code and mandatory S256 PKCE. It never contains or executes OrgMemory realm/client/user/scope/mapper provisioning. Operator and one-time user passwords plus confidential client secrets are read from managed environment or mode-`0600` files and sent through documented environment/stdin channels, not command arguments or output. The API receives only its browser client secret and stable owner subject; the mailbox proxy receives only its own file-mounted client and cookie secrets.

## Deployment

The hardened deployment Compose project owns `memoryos-postgres`, `shared-keycloak`, `memoryos-mailpit`, `memoryos-mailpit-oauth2-proxy`, `memoryos-api`, and `memoryos-web`; the current server is the `staging` environment and no production server exists. PostgreSQL 18.4 stores isolated `memoryos` and `keycloak` databases under distinct non-superuser roles; an empty-volume bootstrap creates only those owners/databases, and the recurring backup profile emits custom-format archives, restore lists, and checksums. Shared Keycloak preserves `https://auth.kl3in.tech` and stable runtime aliases for both products, but MemoryOS contains provisioning only for the `memoryos` realm. OrgMemory continues to own its realm configuration. Staging Mailpit captures Keycloak verification messages with bounded retention, authenticated STARTTLS over the internal network, and a private CA imported by Keycloak. Nginx Proxy Manager exposes `https://memoryos-mail.72-62-193-33.nip.io` only through the dedicated owner-email-allowlisted OAuth2 Proxy; Mailpit itself stays off the public proxy network, and server loopback remains the operator fallback. This is not a public SMTP provider or deliverability claim. The separately protected pgweb deployment uses a dedicated transaction-read-only role against `memoryos-postgres` on the MemoryOS internal network.

The staging application origin is `https://memoryos.72-62-193-33.nip.io`, terminated by Nginx Proxy Manager and forwarded to `memoryos-web:8080`. The confidential `memoryos-web` client retains only the matching HTTPS callback/root/web origin with S256 PKCE; staging's secure JDBC-session cookie is therefore exercised over HTTPS rather than a loopback development rewrite.

## Deferred components

No multi-Organization switcher, broker policy, audit history, OpenFGA client, connector, MCP server, GraphRAG engine, public deployment automation, account-linking endpoint, durable memory screen, chat UI, or background-processing loop exists. Add every deferred component only through a capability-owned vertical slice with a verified production path.