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

The authenticated root route uses a responsive OrgMemory/Onyx-aligned application shell. Desktop provides a 15rem sidebar that folds to a 4rem rail; the folded logo is the expand control and swaps to the sidebar icon on hover or focus. Mobile uses an accessible modal drawer. `New Session` is the selected shell surface, `/admin` provides the separate administration shell, and the account popover exposes only real appearance and admin actions. These are browser-shell surfaces only: no assistant execution, source operation, notification, help, or logout capability is inferred from them.

The web design system remains local to the single application. `styles/tokens.css` owns the monochrome primitives and semantic roles, `styles/theme.css` maps those roles and the Hanken Grotesk type scale into Tailwind v4, and `styles/base.css` owns global element behavior. Product components consume semantic roles and typography presets rather than raw palette or font-size values. Shared `SidebarTab`, `SidebarSection`, and `MenuItem` primitives own navigation/menu interaction. No reusable design-system package, Storybook surface, Style Dictionary pipeline, cross-platform token output, or migration component tree exists without a second consumer.

## Capability boundaries

`core` contains eight closed Spring Modulith modules: `identity`, `organization`, `invitation`, `authorization`, `knowledge`, `ingestion`, `retrieval`, and `assistant`. A capability root package is its public API. Public enums and records are top-level types. Transactional orchestration lives in `application`; SQL, row mapping, and conditional storage operations live in `persistence`. Capability-owned implementation packages cannot be imported by another capability.

`organization` depends only on `identity`. `invitation` depends only on the public APIs of `identity` and `organization`; it owns invitation lifecycle/persistence and coordinates their mandatory transaction ports without importing either capability's persistence. The remaining dependency graph is enforced by Spring Modulith and ArchUnit tests. `core` may use Spring, `JdbcClient`, transactions, or JPA inside a capability boundary; it is not a framework-free domain layer.

`api` scans `io.memoryos`, so capability implementations in `core` register themselves with Spring stereotypes; deployable configuration defines only infrastructure, security, properties, and startup runners. `worker` scans only `io.memoryos.worker` and excludes datasource auto-configuration until it owns a persistence-backed background runtime path, preventing the shared core JDBC starter from requiring unused worker datasource configuration. Static persistence factories and forwarding `@Bean` methods are intentionally absent.

Audit is intentionally absent until a real evidence consumer defines attribution, transaction, retention, access, and export semantics. See [ADR 0003](docs/decisions/0003-defer-audit-until-evidence-consumer.md).

## Persistence and startup

Flyway owns three migrations:

- `V1__create_identity_tables.sql`: stable `actors` and exact `(issuer, subject)` bindings.
- `V2__create_initial_organization_and_sessions.sql`: Organizations, Workspaces, scoped memberships, singleton bootstrap state, and Spring Session JDBC tables.
- `V3__create_organization_invitations.sql`: Invitation-owned digest-only lifecycle rows scoped by Organization/default Workspace.

API startup requires datasource, OIDC, confidential browser-client, and initial Organization configuration. After migration, an `ApplicationRunner` invokes the transactional initial bootstrap. A migration-created singleton row is locked with `SELECT ... FOR UPDATE`; the transaction resolves or creates the exact owner binding, inserts one Organization and default Workspace, grants Organization `OWNER` and Workspace `ADMIN`, and publishes the Organization ID. Concurrent replicas serialize on that row. Identical configuration replays; drift or incomplete state fails startup.

## Authentication

The API composes three ordered security chains:

1. Browser application API (`/api/identity/me` and `/api/invitations/**`): existing JDBC-backed browser sessions or bound bearer identities; the redacted current-invitation lookup is public but reads only an existing session.
2. Remaining `/api/**`: stateless OAuth2 Resource Server bearer authentication.
3. Browser routes: invitation intake/continuation plus OAuth2 Login Authorization Code + PKCE with JDBC-backed sessions.

Both authentication modes validate provider tokens, then resolve exact `(issuer, subject)` to `ActorId`. Bearer requests with no binding fail `401`. Ordinary browser login additionally requires active Organization authority and otherwise redirects to `ACCESS_NOT_PROVISIONED`. A valid invitation continuation is the only alternate browser path: the Invitation transaction binds the exact identity, grants fixed Organization/default-Workspace `MEMBER`, and consumes the invitation before the same `ActorId`-only session is saved. Failure invalidates the partial session. Remaining API routes stay stateless and bearer-only.

On successful browser login, Spring Security session-fixation protection rotates the session ID. The callback replaces `OAuth2AuthenticationToken` with `ActorSessionAuthenticationToken`, explicitly saves a security context whose serializable principal contains only `ActorId`, and uses a discarding authorized-client repository. Provider access, refresh, and raw ID-token state is not retained in Spring Session.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | Health status |
| `GET /api/identity/me` | Valid bound bearer identity or authenticated browser session | `{"actorId":"<uuid>"}` |
| `GET /` | Browser origin | Static application; session state is resolved through `/api/identity/me` |
| `GET /access-not-provisioned` | Browser origin | Public accessible denial state |
| `GET /invite/{secret}` | Public capability link | Digest lookup, redacted JDBC continuation, then invitation landing |
| `/api/invitations/**` | Active Organization owner, except redacted current continuation | Create/list/rotate/revoke lifecycle and recipient landing context |

## External identity provider

Keycloak is the browser credential store and OIDC provider. The reconciliation script creates or reuses the named local initial owner without assigning Keycloak administration roles, retains public PKCE client `memoryos-integration`, and adds confidential `memoryos-web` with Authorization Code and mandatory S256 PKCE. Operator and one-time user passwords plus the browser client secret are read from managed environment values and sent through documented environment/stdin channels, not command arguments or output. The API receives only the browser client secret and stable owner subject; it never receives Keycloak administrator credentials or user passwords.

## Deployment

The API and web application ship as separate commit-labelled containers. The API image is layered Spring Boot; the web image builds immutable Vite assets and serves them through unprivileged Nginx. Both services run non-root with read-only filesystems, bounded temporary storage, dropped capabilities, `no-new-privileges`, rotating logs, health checks, graceful shutdown, and CPU/memory limits. Only `memoryos-web` joins the external proxy network; it proxies backend-owned `/api`, `/oauth2`, `/login`, `/logout`, and `/actuator/health` paths to `memoryos-api` on `shared-infra`, while all public traffic stays on one HTTPS origin. Loopback ports remain available for controlled host diagnostics. Forwarded host and scheme preserve the external HTTPS callback origin.

## Deferred components

No Organization/Workspace switcher, broker policy, audit history, OpenFGA client, connector, MCP server, GraphRAG engine, public deployment automation, account-linking endpoint, durable memory screen, chat UI, or background-processing loop exists. Add every deferred component only through a capability-owned vertical slice with a verified production path.