# Development runtime runbook

## Prerequisites

- JDK 25 and the checked-in Gradle wrapper.
- Node.js 24 with Corepack for the `web/` application.
- Docker with the Compose plugin for production image and topology checks.
- Access to the target Keycloak realm and PostgreSQL database.
- Secrets loaded from managed storage into process environment only; never copy values into Git, docs, Linear, logs, or command history.
- A deployment-managed username for the initial owner. The reconciliation script creates that local Keycloak user with a one-time temporary password when absent and reports its stable Keycloak user ID as the OIDC subject. The user receives no Keycloak administrative role; MemoryOS grants Organization authority from the reported subject.

## Reconcile Keycloak owner and clients

`infrastructure/keycloak/configure-memoryos-realm.sh` creates or reuses the named local initial owner, retains public client `memoryos-integration`, reconciles confidential client `memoryos-web`, enforces Authorization Code with S256 PKCE, and sets the deployment-managed browser client secret.

Required operator environment:

```text
KEYCLOAK_URL
KEYCLOAK_ADMIN_USERNAME
KC_CLI_PASSWORD
MEMORYOS_INITIAL_OWNER_USERNAME
MEMORYOS_INITIAL_OWNER_TEMPORARY_PASSWORD # required only when the user does not exist
MEMORYOS_BROWSER_CLIENT_SECRET
MEMORYOS_BROWSER_REDIRECT_URI # one exact HTTPS callback, or one loopback callback for local verification
```

Run the script from a controlled operator shell with `jq` available. Its account needs only user/client management permissions required by the script; do not grant the application or initial owner those Keycloak permissions. Set `MEMORYOS_BROWSER_REDIRECT_URI` to the one exact deployment callback, for example `https://memoryos.example.com/login/oauth2/code/memoryos`; wildcards and non-loopback HTTP origins are rejected. Use `http://localhost:8080/login/oauth2/code/memoryos` only for local verification. Keycloak reads the operator password from its documented `KC_CLI_PASSWORD` environment variable. User creation, exact redirect configuration, and browser-secret updates are JSON-encoded from environment values and sent to `kcadm` over standard input, so passwords and secrets do not appear in command arguments or output. The initial password is temporary and must be replaced by the owner at first login; replay never resets an existing user's password. The mode-restricted temporary token configuration and generated browser-client document are removed on exit.

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

## Run the production containers

Build immutable API and web images from the same reviewed commit and tag both with the full source SHA:

```text
docker build --build-arg VCS_REF=<40-character-commit> --build-arg BUILD_DATE=<UTC-timestamp> --tag memoryos-api:sha-<40-character-commit> .
docker build --file web/Dockerfile --build-arg VCS_REF=<40-character-commit> --build-arg BUILD_DATE=<UTC-timestamp> --tag memoryos-web:sha-<40-character-commit> .
```

Keep the complete API environment in a mode-`0600` file outside Git. It must contain the variables listed above, use `jdbc:postgresql://shared-postgres:5432/memoryos`, and keep `MEMORYOS_SESSION_COOKIE_SECURE=true`. It must not contain a Keycloak operator credential or owner password.

```text
MEMORYOS_API_IMAGE=memoryos-api:sha-<40-character-commit> \
MEMORYOS_WEB_IMAGE=memoryos-web:sha-<40-character-commit> \
MEMORYOS_ENV_FILE=/apps/memoryos/.env \
docker compose -f infrastructure/deployment/compose.production.yaml config

MEMORYOS_API_IMAGE=memoryos-api:sha-<40-character-commit> \
MEMORYOS_WEB_IMAGE=memoryos-web:sha-<40-character-commit> \
MEMORYOS_ENV_FILE=/apps/memoryos/.env \
docker compose -f infrastructure/deployment/compose.production.yaml up -d --wait
```

Both services join `shared-infra`; only `memoryos-web` joins `proxy-network`. Configure the external reverse proxy to send the complete MemoryOS HTTPS origin to `memoryos-web:8080`. Nginx serves the SPA and proxies backend-owned `/api`, `/oauth2`, `/login`, `/logout`, and `/actuator/health` paths to `memoryos-api:8080`; do not configure CORS or split the browser across origins. Host ports `18080` and `18081` are loopback-only diagnostics.

Both containers run non-root with read-only filesystems, bounded temporary storage, dropped capabilities, `no-new-privileges`, rotating logs, health checks, and CPU/memory limits. Forwarded host and scheme determine the exact OAuth2 callback origin.

Shared PostgreSQL binds only to server loopback port `5555`. Establish an SSH local forward before using the URL above; do not publish the database port:

```powershell
ssh -o ExitOnForwardFailure=yes -N -L 15555:127.0.0.1:5555 <operator>@<shared-postgres-host>
```

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