# MEM-8 verification

Status: local and shared-runtime implementation evidence complete for exact bootstrap, initial-owner browser login, `ActorId`-only JDBC session persistence, and restart replay. A live unprovisioned-user denial, pull request latest-head CI, and review remain required before merge.

## Automated evidence

### Focused bootstrap and compilation

Command:

```powershell
.\gradlew.bat :core:test --tests io.memoryos.organization.persistence.JdbcInitialOrganizationBootstrapperTest :api:compileTestJava --no-daemon
```

Observed 2026-08-19: `BUILD SUCCESSFUL` in 22 seconds. This exercised exact aggregate creation/replay, singleton concurrency, drift rejection, identity rollback, and active-membership resolution; API test sources compiled against the narrowed contract.

### Browser HTTP integration

Command:

```powershell
.\gradlew.bat :api:test --tests io.memoryos.api.security.BrowserAuthenticationIntegrationTest --no-daemon
```

Observed 2026-08-19: `BUILD SUCCESSFUL` in 21 seconds. The same focused suite passed again after adding the forwarded-origin production callback contract.

The test starts the real API HTTP composition with an isolated database and local OIDC issuer. It verifies:

- Authorization Code callback with an S256 challenge matching the server-side verifier;
- HTTPS OAuth2 callback derivation from trusted reverse-proxy forwarding headers;
- exact issuer/subject resolution for the configured initial owner;
- active Organization membership admission;
- session fixation protection;
- explicit persistence of an `ActorId`-only application principal;
- absence of provider access/refresh/raw ID-token markers in serialized JDBC session state; and
- rejection plus session invalidation for a bound actor without Organization authority.

### Keycloak reconciliation smoke

Observed 2026-08-19: `sh -n infrastructure/keycloak/configure-memoryos-realm.sh` passed. A disposable `kcadm` double then exercised the complete script twice. The first pass created the named user with a temporary credential supplied through stdin, reported its stable subject, created both clients, and updated the confidential secret through stdin. The second pass omitted the temporary-password variable, reused the same subject without resetting its credential, and reconciled the existing clients. Captured output contained no password or client secret.

### Container packaging

Observed 2026-08-19: `docker compose ... config` rendered the production service with the exact external networks, loopback-only port, non-root/read-only hardening, health check, log limits, and CPU/memory limits. A clean `docker build` produced the layered `memoryos-api:mem8-validation` image from pinned JDK/JRE image indexes. Inspection proved runtime user `1654:1654`, the source/build labels and Java entrypoint; the 85,023,026-byte runtime includes the BusyBox `wget` used by the health check.

### IDE semantic inspection

JetBrains `get_file_problems` inspected every remaining changed Java, Kotlin DSL, and YAML file with warnings enabled, including the production Compose descriptor and forwarded callback test. The review removed an unused exception, simplified an impossible null branch, made the concurrency-test executor structurally closeable, and removed one redundant forwarded-port test header. Final reinspection returned no errors or warnings.

### Repository gate

Command:

```powershell
.\gradlew.bat clean check --no-daemon
```

The first gate exposed two non-browser test contexts that still disabled OIDC discovery with a blank issuer. Their local test providers were corrected to publish standards-shaped discovery metadata. The affected API contexts then passed in 32 seconds.

Observed final implementation run 2026-08-19: `BUILD SUCCESSFUL` in 21 seconds; 17 actionable tasks, 9 executed, 7 from cache, and 1 up to date. This included all capability, HTTP integration, Spring Modulith, ArchUnit, and composition-root smoke tests. After shared-runtime verification and evidence consolidation, the same `clean check` gate passed again in 11 seconds with 17 actionable tasks, 7 executed, 9 from cache, and 1 up to date.

## Shared production deployment

Observed 2026-08-19 on `zm`: built commit `54893747a459e7ce082ce4fd1348967b590bb707` as `memoryos-api:sha-54893747a459e7ce082ce4fd1348967b590bb707` with image ID `sha256:37262bd304d7c2fc95f8e5daab41ad84ed0890c2e39df0e4832ba1fd9fdefa60`. The installed Compose descriptor has SHA-256 `13ebf41195a70558876b298a5de78371c6cbb1a4d58accb4f82f0bf4f90c0666`, matching the reviewed repository file.

The deployed container is healthy on `shared-infra` and `proxy-network`, publishes only server-loopback port `18080`, runs as `1654:1654` with a read-only root filesystem, drops all capabilities, enables `no-new-privileges`, and is bounded to one CPU and 768 MiB memory. `GET /actuator/health` returned `{"groups":["liveness","readiness"],"status":"UP"}`.

Flyway applied both migrations to shared PostgreSQL. First startup produced exactly one Organization, Workspace, Organization membership, Workspace membership, actor, and external identity binding. The published identifiers were:

- Organization `bd7e443f-c6dc-4f08-b1d5-e36f5e21b6c7`;
- default Workspace `01553cbb-1bba-4b64-adf0-3ee1209804f8`;
- actor `2c04758d-6715-4ad2-ad83-1212c9716e8e`; and
- exact Keycloak subject `0bbc3040-9a06-46ec-b4b0-c546199e3e00`.

The Organization is `ACTIVE`, the actor has active Organization `OWNER` and Workspace `ADMIN` memberships, and the singleton bootstrap-state row publishes the same Organization ID.

## Shared browser and session evidence

Observed 2026-08-19 through an SSH loopback forward: opening `/oauth2/authorization/memoryos` redirected to the live Keycloak realm with `code_challenge_method=S256`. The configured initial owner completed Authorization Code login and returned to `/`, which responded with `{"actorId":"2c04758d-6715-4ad2-ad83-1212c9716e8e"}`.

The browser received one `SESSION` cookie with `HttpOnly`, `Secure`, and `SameSite=Lax`. Shared PostgreSQL held one 1,800-second session whose principal name was the same actor ID and whose only attribute was `SPRING_SECURITY_CONTEXT`. Secret-safe byte inspection found `ActorSessionAuthenticationToken` and no `OAuth2AuthenticationToken`, `OAuth2AuthorizedClient`, or `OidcUser` marker.

## Shared restart replay

Observed 2026-08-19: restarted the exact deployed container and waited for Compose health. Flyway reported the schema current with no migration required. Aggregate cardinalities remained one and every Organization, Workspace, actor, and subject identifier remained unchanged. The pre-restart browser session remained authenticated and `/` returned the same actor ID, proving JDBC session continuity across process restart.

## Remaining runtime evidence

The shared realm has no unprovisioned test identity. The initial owner authenticates but receives `403 Forbidden` for realm-user administration; the available master bootstrap account can read the realm but receives `401 Unauthorized` when creating a realm user. No permission or database bypass was introduced. The exact `ACCESS_NOT_PROVISIONED` callback, invalidated partial session, and zero provider-state persistence remain covered by `BrowserAuthenticationIntegrationTest.rejectsABoundIdentityWithoutOrganizationMembershipAndInvalidatesItsSession`; a live denial still requires a separately provisioned test identity or authorized Keycloak operator.

## Pending external evidence

- Live unprovisioned Keycloak account receives `ACCESS_NOT_PROVISIONED`.
- Pull request latest-head CI and review evidence.

Do not mark MEM-8 delivered or move this directory to `completed/` before those gates and merge.