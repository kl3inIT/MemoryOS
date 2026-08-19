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

## Capability boundaries

`core` contains seven closed Spring Modulith modules: `identity`, `organization`, `authorization`, `knowledge`, `ingestion`, `retrieval`, and `assistant`. A capability root package is its public API. Capability-owned persistence stays beneath that capability and cannot be imported by another capability.

`organization` depends only on `identity`. The remaining dependency graph is enforced by Spring Modulith and ArchUnit tests. `core` may use Spring, `JdbcClient`, transactions, or JPA inside a capability boundary; it is not a framework-free domain layer.

Audit is intentionally absent until a real evidence consumer defines attribution, transaction, retention, access, and export semantics. See [ADR 0003](docs/decisions/0003-defer-audit-until-evidence-consumer.md).

## Persistence and startup

Flyway owns two migrations:

- `V1__create_identity_tables.sql`: stable `actors` and exact `(issuer, subject)` bindings.
- `V2__create_initial_organization_and_sessions.sql`: Organizations, Workspaces, scoped memberships, singleton bootstrap state, and Spring Session JDBC tables.

API startup requires datasource, OIDC, confidential browser-client, and initial Organization configuration. After migration, an `ApplicationRunner` invokes the transactional initial bootstrap. A migration-created singleton row is locked with `SELECT ... FOR UPDATE`; the transaction resolves or creates the exact owner binding, inserts one Organization and default Workspace, grants Organization `OWNER` and Workspace `ADMIN`, and publishes the Organization ID. Concurrent replicas serialize on that row. Identical configuration replays; drift or incomplete state fails startup.

## Authentication

The API composes two ordered security chains:

1. `/api/**`: stateless OAuth2 Resource Server bearer authentication.
2. Browser routes: OAuth2 Login Authorization Code + PKCE with JDBC-backed sessions.

Both paths validate provider tokens, then resolve exact `(issuer, subject)` to `ActorId`. Bearer requests with no binding fail `401`. Browser login additionally requires an active membership in an active Organization; otherwise it invalidates the partial session and redirects to `ACCESS_NOT_PROVISIONED`.

On successful browser login, Spring Security session-fixation protection rotates the session ID. The callback replaces `OAuth2AuthenticationToken` with `ActorSessionAuthenticationToken`, explicitly saves a security context whose serializable principal contains only `ActorId`, and uses a discarding authorized-client repository. Provider access, refresh, and raw ID-token state is not retained in Spring Session.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | Health status |
| `GET /api/identity/me` | Valid bound bearer identity | `{"actorId":"<uuid>"}` |
| `GET /` | Valid bound browser identity with active Organization membership | `{"actorId":"<uuid>"}` |
| `GET /access-not-provisioned` | Public failure state | `403` with `ACCESS_NOT_PROVISIONED` |

## External identity provider

Keycloak is the browser credential store and OIDC provider. The reconciliation script creates or reuses the named local initial owner without assigning Keycloak administration roles, retains public PKCE client `memoryos-integration`, and adds confidential `memoryos-web` with Authorization Code and mandatory S256 PKCE. Operator and one-time user passwords plus the browser client secret are read from managed environment values and sent through documented environment/stdin channels, not command arguments or output. The API receives only the browser client secret and stable owner subject; it never receives Keycloak administrator credentials or user passwords.

## Deployment

The API ships as one commit-labelled, layered Spring Boot container. Its Compose service runs non-root with a read-only filesystem, bounded temporary storage, dropped capabilities, `no-new-privileges`, rotating logs, health checks, graceful shutdown, and CPU/memory limits. It joins the existing `shared-infra` network for PostgreSQL and `proxy-network` for HTTPS ingress; plaintext host access is loopback-only. Framework-processed forwarding headers preserve the external HTTPS origin used to construct the OAuth2 callback.

## Deferred components

No invitation flow, member administration, Organization/Workspace switcher, broker policy, audit history, OpenFGA client, connector, MCP server, GraphRAG engine, public deployment automation, account-linking endpoint, or background-processing loop exists. Invitation onboarding is tracked by MEM-12. Add every deferred component only through a capability-owned vertical slice with a verified production path.