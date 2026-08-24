# Development runtime runbook

## Prerequisites

- JDK 25 and the checked-in Gradle wrapper.
- Node.js 24 with Corepack for the `web/` application.
- Docker with the Compose plugin for production image and topology checks.
- Access to the target Keycloak realm and PostgreSQL database.
- Secrets loaded from managed storage into process environment only; never copy values into Git, docs, Linear, logs, or command history.
- A deployment-managed username for the initial owner. The reconciliation script creates that local Keycloak user with a one-time temporary password when absent and reports its stable Keycloak user ID as the OIDC subject. The user receives no Keycloak administrative role; MemoryOS grants Organization authority from the reported subject.

## Environment boundaries

Infisical `dev` is the developer-local environment. Its shared keys are the runnable baseline; each engineer uses Infisical personal-secret overrides for credentials or endpoints that differ on their machine. `SPRING_PROFILES_ACTIVE=development` selects application-focused DEBUG logging while keeping Spring Security at INFO so authorization headers, tokens, and claims are not expanded into logs.

Start a local API without exporting secret values:

```text
infisical run --env=dev --projectId=<memoryos-project-id> -- .\gradlew.bat :api:bootRun --no-daemon
```

Infisical `staging` is the only server environment. It has its own shared copy of every required MemoryOS key, `SPRING_PROFILES_ACTIVE=staging`, and `MEMORYOS_SESSION_COOKIE_SECURE=true`. The staging Spring profile keeps root and Spring Security logging at INFO, enables DEBUG for MemoryOS, Spring Web, JDBC statements, and transactions, and leaves parameter-value TRACE logging disabled. Keycloak keeps root INFO while enabling DEBUG for event and service categories. There is no production server and the Infisical `prod` environment remains empty.

The server bootstrap file is outside Git with mode `0600` and contains only `INFISICAL_DOMAIN`, `INFISICAL_PROJECT_ID`, `INFISICAL_ENVIRONMENT=staging`, `INFISICAL_CLIENT_ID`, and `INFISICAL_CLIENT_SECRET`. The API entrypoint exchanges those Universal Auth credentials for a 15-minute access token, unsets the client credentials, injects the selected environment, and drops permanently to UID/GID 1654 before Java starts. The staging identity has project `viewer` access only. The current self-hosted Infisical plan rejects trusted-IP restrictions, so compensate with the narrow role, a 90-day client-secret TTL, lockout, owner-only server storage, and scheduled rotation.

### Infisical application key audit

| Key | Secret | Runtime effect and environment rule |
| --- | --- | --- |
| `MEMORYOS_DATABASE_URL` | No | JDBC target. By current policy, both `dev` and `staging` use the staging MemoryOS database; only the API/web processes are local in `dev`. |
| `MEMORYOS_DATABASE_USERNAME` | No | Login role for the MemoryOS database. It must remain `memoryos_app`, never the PostgreSQL platform administrator. |
| `MEMORYOS_DATABASE_PASSWORD` | Yes | Password for `memoryos_app`. Staging cutover updates both Infisical staging and the target role atomically. |
| `MEMORYOS_IDENTITY_ISSUER` | No | Required JWT/OIDC issuer and exact `(issuer, subject)` identity-binding namespace. Changing it breaks existing bindings. |
| `MEMORYOS_IDENTITY_JWK_SET_URI` | No | Explicit signing-key endpoint for resource-server JWT verification; issuer validation still uses `MEMORYOS_IDENTITY_ISSUER`. |
| `MEMORYOS_IDENTITY_AUDIENCE` | No | Required API audience claim; rejects a valid Keycloak token minted for another client/resource. |
| `MEMORYOS_BROWSER_CLIENT_ID` | No | Confidential OAuth2 browser client registration name, currently `memoryos-web`. |
| `MEMORYOS_BROWSER_CLIENT_SECRET` | Yes | OAuth2 authorization-code/token-exchange credential for `memoryos-web`; never a browser/Vite variable. |
| `MEMORYOS_INITIAL_OWNER_SUBJECT` | Sensitive identifier | Stable Keycloak user UUID used to bind or verify the first Organization owner. It is not a username and must not change when names/email change. |
| `MEMORYOS_ORGANIZATION_SLUG` | No | DNS-style slug for the one published initial Organization; startup rejects drift after bootstrap. |
| `MEMORYOS_ORGANIZATION_DISPLAY_NAME` | No | Display name for that Organization; startup rejects drift after bootstrap. |
| `MEMORYOS_DEFAULT_WORKSPACE_SLUG` | No | DNS-style slug for its initial default Workspace; startup rejects drift after bootstrap. |
| `MEMORYOS_DEFAULT_WORKSPACE_DISPLAY_NAME` | No | Display name for that Workspace; startup rejects drift after bootstrap. |
| `MEMORYOS_INITIAL_ORGANIZATION_CHANGE_REFERENCE` | No | Stable operator provenance persisted on the initial Organization and compared on every bootstrap. `MEM-8-initial-owner` means MEM-8 authorized the original aggregate; it is not a per-deploy release label and must not be changed casually. |
| `MEMORYOS_SESSION_COOKIE_SECURE` | No | `true` on HTTPS staging; `false` only for localhost HTTP development. |
| `SPRING_PROFILES_ACTIVE` | No | `development` in Infisical `dev`; `staging` on the server. Selects logging policy only, not alternate business behavior. |

`MEMORYOS_INVITATION_TTL` and `MEMORYOS_SESSION_TIMEOUT` are optional: the checked-in defaults are `72h` and `30m`. Keep them out of Infisical until an environment has an approved reason to override those contracts.

## OMP code intelligence and debugging

Start OMP from the repository root so it loads `.omp/lsp.json`, `.omp/dap.json`, the project skills, and the JetBrains MCP endpoint.

Java code intelligence uses JDTLS. TypeScript and TSX use the repository-pinned TypeScript 7 native server through `node web/node_modules/typescript/bin/tsc --lsp --stdio`. Do not install `typescript-language-server` for this repository: it requires the legacy `tsserver.js`, which TypeScript 7 no longer ships. The checked-in native configuration keeps LSP diagnostics, symbols, hover, references, and refactors on the same TypeScript version as `pnpm check`.

OMP JavaScript debugging requires Microsoft `vscode-js-debug`; install the pinned DAP release under `~/.local/opt/js-debug` so `src/dapDebugServer.js` is auto-discovered. JVM debugging requires `fwcd/kotlin-debug-adapter` on `PATH`; `.omp/dap.json` supplies the MemoryOS Gradle root, API main class, and loopback attach defaults. Never place runtime credentials in either config.

For Spring Boot attach debugging, load the normal managed runtime environment first, then start the checked-in wrapper in suspended JDWP mode:

```powershell
.\gradlew.bat :api:bootRun --debug-jvm --no-daemon
```

The JVM listens on loopback port `5005` and waits for the debugger. OMP `17.3.5` currently deadlocks with `kotlin-debug-adapter 0.4.4` during the DAP `initialized`/`configurationDone` handshake even though a direct DAP client attaches successfully. Until OMP fixes that protocol ordering, use the IntelliJ debugger for JVM breakpoints and do not claim OMP Java DAP verification. JavaScript DAP is verified against `web/scripts/assert-playwright-image.ts`.

## Reconcile Keycloak owner and clients

`infrastructure/keycloak/configure-memoryos-realm.sh` creates or reuses the named local initial owner, verifies its deployment-managed email, enables email-as-username self-registration with required email verification, configures realm SMTP, retains public client `memoryos-integration`, reconciles confidential client `memoryos-web`, enforces Authorization Code with S256 PKCE, and sets the deployment-managed browser client secret.

Required operator environment:

```text
KEYCLOAK_URL
KEYCLOAK_ADMIN_USERNAME
KC_CLI_PASSWORD
MEMORYOS_INITIAL_OWNER_USERNAME
MEMORYOS_INITIAL_OWNER_EMAIL
MEMORYOS_INITIAL_OWNER_TEMPORARY_PASSWORD # required only when the user does not exist
MEMORYOS_BROWSER_CLIENT_SECRET
MEMORYOS_BROWSER_REDIRECT_URI # one exact HTTPS callback, or one loopback callback for local verification
MEMORYOS_KEYCLOAK_SMTP_HOST
MEMORYOS_KEYCLOAK_SMTP_PORT # defaults to 587
MEMORYOS_KEYCLOAK_SMTP_FROM
MEMORYOS_KEYCLOAK_SMTP_FROM_DISPLAY_NAME # defaults to MemoryOS
MEMORYOS_KEYCLOAK_SMTP_AUTH # defaults to true
MEMORYOS_KEYCLOAK_SMTP_USERNAME # required when auth is true
MEMORYOS_KEYCLOAK_SMTP_PASSWORD # required when auth is true
MEMORYOS_KEYCLOAK_SMTP_STARTTLS # defaults to true
MEMORYOS_KEYCLOAK_SMTP_SSL # defaults to false; exactly one transport flag is true
```

Run the script from a controlled operator shell with `jq` available. Its account needs realm, user, and client management permissions required by the script; do not grant the application, owner, or invited members those Keycloak permissions. Set `MEMORYOS_BROWSER_REDIRECT_URI` to one exact deployment callback; wildcards and non-loopback HTTP origins are rejected. SMTP credentials remain managed operator values. Keycloak receives them in a partial realm update over stdin and sends recipient verification email itself; MemoryOS does not call the Admin API, hold SMTP credentials, or send account-verification mail. The script reads operator and SMTP passwords from environment and never prints them.

Record the script's `subject=<uuid>` result in managed deployment configuration as `MEMORYOS_INITIAL_OWNER_SUBJECT`. Do not use username or email in its place.

## Run the API

Set runtime configuration:

```powershell
$env:MEMORYOS_IDENTITY_ISSUER = "https://auth.kl3in.tech/realms/memoryos"
$env:MEMORYOS_IDENTITY_JWK_SET_URI = "https://auth.kl3in.tech/realms/memoryos/protocol/openid-connect/certs"
$env:MEMORYOS_IDENTITY_AUDIENCE = "memoryos-api"

$env:MEMORYOS_BROWSER_CLIENT_ID = "memoryos-web"
$env:MEMORYOS_BROWSER_CLIENT_SECRET = "<load from managed runtime secret>"

$env:MEMORYOS_DATABASE_URL = "jdbc:postgresql://127.0.0.1:15555/memoryos"
$env:MEMORYOS_DATABASE_USERNAME = "memoryos_app"
$env:MEMORYOS_DATABASE_PASSWORD = "<load from managed runtime secret>"

$env:MEMORYOS_INITIAL_OWNER_SUBJECT = "<stable Keycloak user ID>"
$env:MEMORYOS_ORGANIZATION_SLUG = "tasco"
$env:MEMORYOS_ORGANIZATION_DISPLAY_NAME = "Tasco"
$env:MEMORYOS_DEFAULT_WORKSPACE_SLUG = "default"
$env:MEMORYOS_DEFAULT_WORKSPACE_DISPLAY_NAME = "Tasco Default Workspace"
$env:MEMORYOS_INITIAL_ORGANIZATION_CHANGE_REFERENCE = "<approved deployment/change reference>"

$env:MEMORYOS_SESSION_COOKIE_SECURE = "false" # localhost HTTP verification only

.\gradlew.bat :api:bootRun
```

Production HTTPS keeps `MEMORYOS_SESSION_COOKIE_SECURE` unset so it defaults to `true`.

## Run the web application

With the API on loopback port `18080`:

```powershell
corepack enable
cd web
pnpm install --frozen-lockfile
pnpm dev
```

Vite listens on `127.0.0.1:8080` and proxies `/api`, `/oauth2`, `/login/oauth2`, `/logout`, and `/actuator` to `MEMORYOS_API_URL`, which defaults to `http://127.0.0.1:18080`. Open the exact loopback origin registered in Keycloak so the generated callback uses the same host. The loopback-only development proxy removes the production `Secure` attribute from response cookies because local verification uses HTTP; it preserves every other cookie attribute. Production Nginx never performs this rewrite.

## Run the hardened staging stack

MemoryOS Compose owns PostgreSQL, shared Keycloak, API, and web. Copy [`staging.env.example`](../../infrastructure/deployment/staging.env.example) to a mode-`0600` file outside Git for the current server and load every required managed value. The PostgreSQL service creates isolated `memoryos` and `keycloak` databases only on an empty volume. The Keycloak database contains both products' runtime realm data, but this repository provisions only the `memoryos` realm.

Build immutable API and web images from the same reviewed commit and tag both with the full source SHA:

```text
docker build --build-arg VCS_REF=<40-character-commit> --build-arg BUILD_DATE=<UTC-timestamp> --tag memoryos-api:sha-<40-character-commit> .
docker build --file web/Dockerfile --build-arg VCS_REF=<40-character-commit> --build-arg BUILD_DATE=<UTC-timestamp> --tag memoryos-web:sha-<40-character-commit> .
```

Validate and start:

```text
docker compose \
  --env-file /apps/memoryos/.env.staging \
  -f infrastructure/deployment/compose.production.yaml \
  config --quiet

docker compose \
  --env-file /apps/memoryos/.env.staging \
  -f infrastructure/deployment/compose.production.yaml \
  up -d --wait
```

Only `memoryos-web` and shared Keycloak join the external proxy network. PostgreSQL binds to server loopback port `5556` by default; Keycloak, API, and web diagnostics default to `18180`, `18080`, and `18081`. Shared Keycloak keeps `orgmemory-keycloak`, `memoryos-keycloak`, and `keycloak` aliases while public issuers remain under `https://auth.kl3in.tech`.

For local database access:

```powershell
ssh -o ExitOnForwardFailure=yes -N -L 15555:127.0.0.1:5556 <operator>@<memoryos-host>
```

Migrating the retained MemoryOS and Keycloak databases from the legacy shared PostgreSQL deployment requires the backup-first [shared runtime migration runbook](shared-runtime-migration.md). Do not point writers at a fresh target or delete source data before its restore and rollback gates pass.

## Startup contract

Flyway creates identity, Organization, membership, singleton bootstrap-state, and JDBC-session tables. API startup then transactionally creates or verifies:

- the exact `(MEMORYOS_IDENTITY_ISSUER, MEMORYOS_INITIAL_OWNER_SUBJECT)` actor binding;
- one Organization and one default Workspace;
- Organization `OWNER` and Workspace `ADMIN` memberships; and
- the deployment change reference.

The singleton database row serializes concurrent replicas. Restart with identical configuration reuses the aggregate. Changed subject, names, slugs, reference, statuses, cardinality, or memberships fails startup rather than mutating authority. Repair requires an explicitly reviewed persistence recovery; never bypass drift with a temporary profile or convenience endpoint.

## Runtime checks

| Endpoint | Access | Expected result |
| --- | --- | --- |
| `GET /actuator/health` | Public | API health through the web gateway |
| `GET /api/identity/me` | Bound bearer JWT or authenticated browser session | `{"actorId":"<uuid>"}` |
| `GET /` | No browser session | Sign-in state with `/oauth2/authorization/memoryos` action |
| `GET /` | Initial owner after Keycloak login | Authenticated `New Session` application shell |
| `GET /access-not-provisioned` | Public browser route | Accessible `ACCESS_NOT_PROVISIONED` explanation |

Open `/oauth2/authorization/memoryos` to start browser login. Confirm the Keycloak request contains `code_challenge_method=S256`. After callback, confirm the session cookie changes, `/api/identity/me` returns the bootstrapped actor ID, refresh retains the authenticated shell, and an unprovisioned Keycloak account receives `ACCESS_NOT_PROVISIONED`. The browser shell does not display the raw actor UUID.

## Run the worker

```powershell
.\gradlew.bat :worker:bootRun
```

The foundation worker exits cleanly because no durable job loop exists.

## Repository verification

```powershell
.\gradlew.bat clean check --no-daemon
cd web
pnpm check
pnpm test:e2e
```

The Gradle gate compiles all server modules, runs capability and integration tests, verifies Spring Modulith and ArchUnit boundaries, and starts both composition roots in tests. The frontend gate verifies generated-client and route-tree freshness, lint, formatting, TypeScript, unit behavior, and the production bundle; Playwright exercises signed-out, authenticated, and unprovisioned browser states against its own loopback Vite and backend processes, so a separately running development server is not reused.