# MEM-53 implementation plan: local and staging inspection tooling

## Contract and lifecycle

- [x] Create and start MEM-53 in Linear with the approved local/staging tooling scope.
- [x] Verify Arconia PostgreSQL fixed-port and profile behavior, pgweb flags, Redis Insight configuration, OAuth2 Proxy role enforcement, and pinned image digests from current sources.
- [x] Add MEM-53 to the repository active-increment map and roadmap.
- [x] Move merged MEM-42 and MEM-45 increment evidence to completed and reconcile their delivered roadmap entries.

## Development services

- [x] Add Arconia PostgreSQL Dev Services only to the API development/test dependency set.
- [x] Rename Arconia automatic activation from `dev` to `development` in API and worker.
- [x] Configure API-owned PostgreSQL Dev Services on fixed port `55432` in development and disable it in tests.
- [x] Configure worker development datasource access to the API-owned PostgreSQL service.
- [x] Configure worker-owned Redis Dev Services on fixed port `56379` in development while keeping isolated tests independent.
- [x] Document startup order, lifecycle, fixed ports, credentials, and optional local inspection-tool commands.

## Compose separation

- [x] Extract environment-independent PostgreSQL, Keycloak, API, worker, web, volumes, and networks into `compose.base.yaml`.
- [x] Reduce `compose.production.yaml` to a no-tools production overlay.
- [x] Add `compose.staging.yaml` for Mailpit, ACL-provisioned TLS Redis, PostgreSQL inspector bootstrap, pgweb, Redis Insight, and both OAuth2 Proxies.
- [x] Add standalone loopback-only `compose.local-tools.yaml` for fixed host Dev Service ports.
- [x] Keep raw staging tool services private and expose only loopback OAuth proxy endpoints `18026` and `18027`.

## Read-only inspection controls

- [x] Add idempotent PostgreSQL role bootstrap for `memoryos_pgweb` with current and default read-only privileges.
- [x] Configure pgweb with readonly, session lock, SSH denial, passfile, bounded queries, and no browser auto-open.
- [x] Add idempotent Redis ACL bootstrap for a namespace-bounded read-only inspection principal.
- [x] Configure persistent encrypted Redis Insight with database management disabled and a preconfigured Redis connection.
- [x] Require all staging tool credentials, client secrets, cookie secrets, and encryption keys through file-backed or controlled reconciliation boundaries without committed defaults.

## Keycloak SSO

- [x] Preserve and reconcile the `memoryos-pgweb` confidential client with exact callback and PKCE S256.
- [x] Add a separate `memoryos-redisinsight` confidential client and cookie boundary.
- [x] Reconcile realm role `memoryos-inspector` and assign it only to the realm-local `admin` user.
- [x] Require external user bootstrap fields only when realm-local `admin` creation is necessary; do not expose the master realm.
- [x] Update the Keycloak verification path for positive inspector and negative ordinary-user authorization.

## Verification

- [x] Render staging, production, and local Compose combinations with validation-only environment values.
- [x] Prove production output contains no inspection or Mailpit services and no inspection ports.
- [x] Exercise fixed-port API/worker development services without duplicate PostgreSQL or Redis containers.
- [ ] Exercise pgweb and Redis Insight health through their OAuth proxies.
- [ ] Prove realm-local `admin` access and denial for a user lacking `memoryos-inspector`.
- [x] Prove pgweb write denial and Redis ACL write/admin denial while read-only inspection succeeds.
- [x] Inspect changed Java, Kotlin DSL, YAML, properties, and XML files with JetBrains warnings enabled, then compile.
- [x] Run the repository gate after focused runtime verification.

## Consolidation

- [x] Update `ARCHITECTURE.md`, the development runtime runbook, deployment examples, and applicable verification matrices with verified facts only.
- [x] Record exact verification evidence in `verification.md`.
- [x] Keep MEM-53 active until its pull request merges; retain the stopped external pgweb deployment for rollback until staging acceptance.
