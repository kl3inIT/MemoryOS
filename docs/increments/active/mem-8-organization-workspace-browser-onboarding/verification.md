# MEM-8 verification

Status: local implementation, repository verification, and shared Keycloak/runtime-configuration preparation complete. Shared PostgreSQL API/bootstrap/browser evidence, pull request CI, and review remain required before merge.

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

Observed final run 2026-08-19: `BUILD SUCCESSFUL` in 21 seconds; 17 actionable tasks, 9 executed, 7 from cache, and 1 up to date. This included all capability, HTTP integration, Spring Modulith, ArchUnit, and composition-root smoke tests.

## Shared environment preparation

Observed 2026-08-19 on `zm`: reused the live `memoryos` realm, resolved existing local owner `admin` to stable subject `0bbc3040-9a06-46ec-b4b0-c546199e3e00`, and confirmed that the supplied user authenticates but cannot administer realm users. The live Keycloak deployment's existing bootstrap operator was consumed internally without exposing its credential. Confidential client `memoryos-web` was created with Standard Flow only, localhost callbacks, mandatory S256 PKCE, and a generated managed secret. `/apps/memoryos/.env` now contains the API-only runtime configuration with mode `600`; operator and owner passwords are absent. Shared database `memoryos`, role `memoryos_app`, and the existing mode-`600` database secret were reused.

## Pending runtime evidence

On shared infrastructure:

1. start the API against shared PostgreSQL with the exact deployment configuration;
2. prove first startup creates one aggregate and a second identical startup replays it;
3. complete browser login and observe the expected `ActorId` at `/`;
4. prove an unprovisioned Keycloak account receives `ACCESS_NOT_PROVISIONED`; and
5. record secret-safe database evidence for aggregate cardinality and session state.

## Pending external evidence

- Shared PostgreSQL API/bootstrap/browser flow listed above.
- Pull request latest-head CI and review evidence.

Do not mark MEM-8 delivered or move this directory to `completed/` before those gates and merge.