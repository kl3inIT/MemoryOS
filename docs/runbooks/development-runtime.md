# Development runtime runbook

## Prerequisites

- JDK 25 and the checked-in Gradle wrapper.
- Node.js 24 with Corepack for the `web/` application.
- Docker with the Compose plugin for production image and topology checks.
- Access to the target Keycloak realm and PostgreSQL database.
- Secrets loaded from managed storage into process environment only; never copy values into Git, docs, Linear, logs, or command history.
- A deployment-managed username for the initial owner. The reconciliation script creates that local Keycloak user with a one-time temporary password when absent and reports its stable Keycloak user ID as the OIDC subject. The user receives no Keycloak administrative role; MemoryOS grants Tenant authority from the reported subject.

## Environment boundaries

Infisical `dev` is the developer-local environment. Its shared keys are the runnable baseline; each engineer uses Infisical personal-secret overrides for credentials or endpoints that differ on their machine. Arconia reads `META-INF/arconia-bootstrap.properties` and activates only `development` in `bootRun`: the API owns PostgreSQL on fixed host port `55432`, while the worker connects to that database and owns Redis on fixed host port `56379`. The profile selects application-focused DEBUG logging while keeping Spring Security at INFO so authorization headers, tokens, and claims are not expanded into logs.

Start a local API without exporting secret values:

```text
infisical run --env=dev --projectId=<memoryos-project-id> -- .\gradlew.bat :api:bootRun --no-daemon
```

Infisical `staging` is the only server environment. It has its own shared copy of every required MemoryOS key, `SPRING_PROFILES_ACTIVE=staging`, and `MEMORYOS_SESSION_COOKIE_SECURE=true`. The staging Spring profile keeps root and Spring Security logging at INFO, enables DEBUG for MemoryOS, Spring Web, JDBC statements, and transactions, and leaves parameter-value TRACE logging disabled. Keycloak keeps root INFO while enabling DEBUG for event and service categories. There is no production server and the Infisical `prod` environment remains empty.

The server bootstrap file is outside Git with mode `0600` and contains only `INFISICAL_DOMAIN`, `INFISICAL_PROJECT_ID`, `INFISICAL_ENVIRONMENT=staging`, `INFISICAL_CLIENT_ID`, and `INFISICAL_CLIENT_SECRET`. The API entrypoint exchanges those Universal Auth credentials for a 15-minute access token, unsets the client credentials, injects the selected environment, and drops permanently to UID/GID 1654 before Java starts. The staging identity has project `viewer` access only. The current self-hosted Infisical plan rejects trusted-IP restrictions, so compensate with the narrow role, a 90-day client-secret TTL, lockout, owner-only server storage, and scheduled rotation.

### Infisical application key audit

| Key | Secret | Runtime effect and environment rule |
| --- | --- | --- |
| `MEMORYOS_DATABASE_URL` | No | JDBC target shared by API and worker. API owns Flyway; worker starts only after API health proves the schema current. |
| `MEMORYOS_DATABASE_USERNAME` | No | Login role shared by API and worker for the MemoryOS database. It must remain `memoryos_app`, never the PostgreSQL platform administrator. |
| `MEMORYOS_DATABASE_PASSWORD` | Yes | Password for `memoryos_app`, consumed by API and worker. Staging cutover updates both Infisical staging and the target role atomically. |
| `MEMORYOS_IDENTITY_ISSUER` | No | Required JWT/OIDC issuer and exact `(issuer, subject)` identity-binding namespace. Changing it breaks existing bindings. |
| `MEMORYOS_IDENTITY_JWK_SET_URI` | No | Explicit signing-key endpoint for resource-server JWT verification; issuer validation still uses `MEMORYOS_IDENTITY_ISSUER`. |
| `MEMORYOS_IDENTITY_AUDIENCE` | No | Required API audience claim; rejects a valid Keycloak token minted for another client/resource. |
| `MEMORYOS_BROWSER_CLIENT_ID` | No | Confidential OAuth2 browser client registration name, currently `memoryos-web`. |
| `MEMORYOS_BROWSER_CLIENT_SECRET` | Yes | OAuth2 authorization-code/token-exchange credential for `memoryos-web`; never a browser/Vite variable. |
| `MEMORYOS_KEYCLOAK_ADMIN_SERVER_URL` | No | Internal Keycloak base URL used only by the Identity-owned invitation provisioner. Staging uses the shared Keycloak container alias; browser issuer URLs remain public and exact. |
| `MEMORYOS_KEYCLOAK_ADMIN_CLIENT_SECRET` | Yes | Client-credentials secret for realm-local `memoryos-user-provisioner`; never a browser variable or operator administrator credential. |
| `MEMORYOS_INVITATION_ACTIVATION_REDIRECT_URI` | No | Exact public `https://<memoryos-origin>/invite/activate` return target registered on `memoryos-web`; wildcards are forbidden. |
| `MEMORYOS_TENANT_ID` | No | Required stable UUID for the one deployment Tenant. It must match `tenants.id` and `tenant_bootstrap_state.tenant_id`; never rotate it during an ordinary deployment. |
| `MEMORYOS_INITIAL_OWNER_SUBJECT` | Sensitive identifier | Stable Keycloak user UUID used to bind or verify the first Tenant owner. It is not a username and must not change when names/email change. |
| `MEMORYOS_TENANT_SLUG` | No | DNS-style slug for the one deployment Tenant; startup rejects drift after bootstrap. |
| `MEMORYOS_TENANT_DISPLAY_NAME` | No | Display name for that Tenant; startup rejects drift after bootstrap. |
| `MEMORYOS_INITIAL_TENANT_CHANGE_REFERENCE` | No | Stable operator provenance persisted on the initial Tenant and compared on every bootstrap. It is not a per-deploy release label and must not be changed casually. |
| `MEMORYOS_SESSION_COOKIE_SECURE` | No | `true` on HTTPS staging; `false` only for localhost HTTP development. |
| `MEMORYOS_WORKER_PORT` | No | Internal worker actuator port; default `8081`. It is not published publicly. |
| `MEMORYOS_WORKER_BATCH_SIZE` | No | Bounded index/cleanup claim batch; default `8`, runtime-clamped to `1..32`. |
| `MEMORYOS_WORKER_IDLE_DELAY` | No | Delay between scheduled claim loops; default `1s` until the Redis consumer cutover. |
| `MEMORYOS_REDIS_HOST` | No | Staging uses Compose alias `redis`; development is supplied by worker-owned Arconia Redis Dev Services. |
| `MEMORYOS_REDIS_PORT` | No | Staging Redis TLS port `6379`; development host port `56379`. |
| `MEMORYOS_REDIS_USERNAME` | No | Staging worker ACL username `memoryos-worker`. |
| `MEMORYOS_REDIS_PASSWORD` | Yes | Worker ACL password. Staging overrides any Infisical value from the mode-`0600` `MEMORYOS_REDIS_WORKER_PASSWORD_FILE` mounted into the worker; other production deployments supply it through their managed secret source. |
| `MEMORYOS_REDIS_SSL_ENABLED` | No | `true` in staging. `application-staging.yaml` trusts only the mounted Redis CA through the `memoryos-redis` SSL bundle. |
| `MEMORYOS_REDIS_CONNECT_TIMEOUT` | No | Bounded Redis connection timeout; staging default `2s`. |
| `MEMORYOS_REDIS_COMMAND_TIMEOUT` | No | Bounded Redis command timeout; staging default `2s`. |
| `MEMORYOS_REDIS_POOL_MAX_ACTIVE` | No | Maximum worker Redis connections; staging default `8`. |
| `MEMORYOS_REDIS_POOL_MAX_IDLE` | No | Maximum idle worker Redis connections; staging default `8`. |
| `MEMORYOS_REDIS_POOL_MIN_IDLE` | No | Minimum idle worker Redis connections; staging default `0`. |
| `MEMORYOS_REDIS_POOL_MAX_WAIT` | No | Bounded wait for a pooled Redis connection; staging default `2s`. |
| `MEMORYOS_SCHEDULER_NAME` | No | Required production db-scheduler instance identity. It must be stable for one worker instance and unique across concurrent replicas. |
| `SPRING_PROFILES_ACTIVE` | No | Arconia `bootRun` activates `development` from the bootstrap properties file. Staging Compose forces API `staging` and worker `production,staging`; production Compose forces `production`. |

`MEMORYOS_INVITATION_TTL`, `MEMORYOS_SESSION_TIMEOUT`, the current PostgreSQL worker-loop tuning keys, and Redis timeout/pool tuning keys are optional. Keep them out of Infisical until an environment has an approved reason to override checked-in defaults; production Redis identity, authentication, TLS, and scheduler-name values are required.

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

`infrastructure/keycloak/configure-memoryos-realm.sh` creates or reuses the named local initial owner, disables public self-registration, requires verified email, configures realm SMTP, retains public client `memoryos-integration`, reconciles confidential `memoryos-web`, `memoryos-mailpit`, `memoryos-pgweb`, and `memoryos-redisinsight` with Authorization Code and mandatory S256 PKCE, and creates confidential service-account client `memoryos-user-provisioner`. It creates realm role `memoryos-inspector`, assigns it only to that realm-local initial owner, and exposes that role only to the two inspection clients. The master bootstrap administrator is never an inspection identity or client audience. The provisioner receives only realm-local `manage-users`; reconciliation fails closed if broader direct `realm-management` roles are present.

Required operator environment:

```text
KEYCLOAK_URL
KEYCLOAK_ADMIN_USERNAME
KEYCLOAK_ADMIN_REALM # defaults to master for the bootstrap administrator
KC_CLI_PASSWORD
MEMORYOS_INITIAL_OWNER_USERNAME
MEMORYOS_INITIAL_OWNER_EMAIL
MEMORYOS_INITIAL_OWNER_SUBJECT # optional exact existing subject; prevents username-only rediscovery
MEMORYOS_INITIAL_OWNER_TEMPORARY_PASSWORD # required only when the user does not exist
MEMORYOS_BROWSER_CLIENT_SECRET
MEMORYOS_BROWSER_REDIRECT_URI # one exact HTTPS callback, or one loopback callback for local verification
MEMORYOS_MAILPIT_PUBLIC_URL # exact HTTPS nip.io origin
MEMORYOS_MAILPIT_OAUTH2_CLIENT_SECRET
MEMORYOS_PGWEB_PUBLIC_URL # exact HTTPS origin
MEMORYOS_PGWEB_OAUTH2_CLIENT_SECRET
MEMORYOS_REDISINSIGHT_PUBLIC_URL # exact HTTPS origin
MEMORYOS_REDISINSIGHT_OAUTH2_CLIENT_SECRET
MEMORYOS_KEYCLOAK_PROVISIONER_CLIENT_SECRET
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

Run the script from a controlled operator shell with `jq` available. The bootstrap administrator authenticates in `master` while every read and write remains explicitly scoped to the `memoryos` target realm; it is never exposed to an inspection client. Set both inspection URLs to exact HTTPS origins without wildcards, callbacks, or trailing slashes. The script assigns `memoryos-inspector` only to the already reconciled initial owner, revokes stale grants of that dedicated role from every other realm user, and preserves the owner's credential. This is compatible with realms that enforce email-as-username and avoids a second privileged local account. The pgweb and Redis Insight client/cookie secrets remain separate. Store client secrets outside Git and mount the matching files into OAuth2 Proxy.

If a future invitee email is already owned by an unrelated unverified Keycloak user, invitation issue fails closed. An operator must inspect that exact user in the `memoryos` realm, confirm ownership out of band, and delete or repair it through the Keycloak admin console before retrying. MemoryOS never takes over or deletes the account automatically.

Record the script's `subject=<uuid>` result in managed deployment configuration as `MEMORYOS_INITIAL_OWNER_SUBJECT`. Do not use username or email in its place.

## Run the API

Set runtime configuration:

```powershell
$env:MEMORYOS_IDENTITY_ISSUER = "https://auth.kl3in.tech/realms/memoryos"
$env:MEMORYOS_IDENTITY_JWK_SET_URI = "https://auth.kl3in.tech/realms/memoryos/protocol/openid-connect/certs"
$env:MEMORYOS_IDENTITY_AUDIENCE = "memoryos-api"

$env:MEMORYOS_BROWSER_CLIENT_ID = "memoryos-web"
$env:MEMORYOS_BROWSER_CLIENT_SECRET = "<load from managed runtime secret>"
$env:MEMORYOS_KEYCLOAK_ADMIN_SERVER_URL = "http://127.0.0.1:18180"
$env:MEMORYOS_KEYCLOAK_ADMIN_CLIENT_SECRET = "<load from managed runtime secret>"
$env:MEMORYOS_INVITATION_ACTIVATION_REDIRECT_URI = "http://127.0.0.1:8080/invite/activate"

# Do not set MEMORYOS_DATABASE_* for local bootRun. The API-owned PostgreSQL
# Dev Service supplies arconia/arconia/arconia on fixed host port 55432.

$env:MEMORYOS_TENANT_ID = "<stable deployment Tenant UUID>"
$env:MEMORYOS_INITIAL_OWNER_SUBJECT = "<stable Keycloak user ID>"
$env:MEMORYOS_TENANT_SLUG = "tasco"
$env:MEMORYOS_TENANT_DISPLAY_NAME = "Tasco"
$env:MEMORYOS_INITIAL_TENANT_CHANGE_REFERENCE = "<approved deployment/change reference>"

$env:MEMORYOS_SESSION_COOKIE_SECURE = "false" # localhost HTTP verification only

.\gradlew.bat :api:bootRun
```

The API process owns the PostgreSQL Dev Service lifecycle. Start it before the worker and stop it last. The worker development profile connects to `jdbc:postgresql://localhost:55432/arconia` and never creates a second PostgreSQL container. Arconia 0.30 fixed ports are published by Testcontainers on Docker's host interfaces; keep the developer firewall enabled when the machine is on a non-private network.

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

Optional read-only local viewers connect to the fixed Dev Service ports and bind only to loopback:

```powershell
$env:MEMORYOS_REDISINSIGHT_ENCRYPTION_KEY = "<local persistent random value>"
docker compose -f infrastructure/deployment/compose.local-tools.yaml up -d --wait
```

Open pgweb at `http://127.0.0.1:18026` and Redis Insight at `http://127.0.0.1:18027`. The local pgweb uses Arconia's disposable development credentials and read-only mode. Local Redis has no credential; Redis Insight database management remains disabled.

## Run the hardened staging stack

MemoryOS staging composes `compose.base.yaml` plus `compose.staging.yaml`. The base owns PostgreSQL, shared Keycloak, API, worker, and web; the staging overlay adds Mailpit, TLS Redis, read-only inspector bootstrap jobs, pgweb, Redis Insight, and their OAuth2 Proxies. Copy [`staging.env.example`](../../infrastructure/deployment/staging.env.example) to a mode-`0600` file outside Git and load every required managed value. That file owns the stable Tenant ID, slug, display name, bootstrap change reference, and non-secret worker tuning; Infisical continues to own database, identity, and browser secrets. The same file-backed worker password starts Redis and is mounted into the worker, avoiding a duplicated secret value. API runs Flyway before becoming healthy; worker depends on API and Redis health and requires datasource/Redis/db-scheduler readiness. PostgreSQL creates isolated `memoryos` and `keycloak` databases only on an empty volume.

### Publish the staging application

The temporary staging origin is `https://memoryos.72-62-193-33.nip.io`. Configure Nginx Proxy Manager to forward HTTP to `memoryos-web:8080` on `proxy-network` with WebSockets, exploit blocking, forced HTTPS, HTTP/2, HSTS, and an exact-domain Let's Encrypt certificate. Reconcile `memoryos-web` with:

```text
MEMORYOS_BROWSER_REDIRECT_URI=https://memoryos.72-62-193-33.nip.io/login/oauth2/code/memoryos
```

The reconciliation derives the public origin, sets it as the client root/web origin, and retains only that exact callback. Open the HTTPS origin—not a server loopback port—so the staging API's `Secure` JDBC-session cookie survives the OAuth callback.

### Provision the staging mailbox

Mailpit captures development verification mail and never relays it to external recipients. SMTP is reachable only as `mailpit:1025` on the internal Compose network, requires basic authentication after STARTTLS, and uses a private CA trusted by Keycloak through `KC_TRUSTSTORE_PATHS`. Mailpit itself never joins the public proxy network.

Before the first start, create the persistent mailbox directory, SMTP material, OAuth2 client/cookie secrets, and exact owner-email allowlist. `MEMORYOS_MAILPIT_ALLOWED_EMAIL` must be the initial owner's verified Keycloak email:

```text
MEMORYOS_MAILPIT_ALLOWED_EMAIL=<verified-owner-email> \
MEMORYOS_MAILPIT_DATA_DIRECTORY=/apps/memoryos/mailpit \
  infrastructure/mailpit/provision-staging-secrets.sh /apps/memoryos/secrets/mailpit
```

The command is idempotent, adds the OAuth2 files without rotating an existing complete SMTP set, refuses partial secret sets, and prints only the SMTP certificate fingerprint. Every generated file remains mode `0600`. Copy the Mailpit keys from `staging.env.example` into `/apps/memoryos/.env.staging`; set `MEMORYOS_MAILPIT_UID` and `MEMORYOS_MAILPIT_GID` to the owner of `/apps/memoryos/mailpit`.

Reconcile Keycloak with `MEMORYOS_KEYCLOAK_SMTP_HOST=mailpit`, port `1025`, authentication and STARTTLS enabled, SSL disabled, username `memoryos-keycloak`, and the generated SMTP password. Load `MEMORYOS_MAILPIT_OAUTH2_CLIENT_SECRET` from `oauth2-client-secret.txt` without printing it and use `https://memoryos-mail.72-62-193-33.nip.io` as `MEMORYOS_MAILPIT_PUBLIC_URL`. The configured From address may use a reserved development domain because Mailpit captures rather than relays the message.

The Compose-owned `memoryos-mailpit-oauth2-proxy` is the only public route to the mailbox. It uses Keycloak OIDC, S256 PKCE, a secure minimal cookie, file-mounted client/cookie secrets, and `oauth2-allowed-emails.txt`; unlike the existing pgweb proxy, it does not admit every realm email. Configure Nginx Proxy Manager with:

```text
domain=memoryos-mail.72-62-193-33.nip.io
scheme=http
forward-host=memoryos-mailpit-oauth2-proxy
forward-port=4180
websockets=true
block-exploits=true
force-ssl=true
http2=true
hsts=true
certificate=Let's Encrypt for the exact domain
```

Operators retain a loopback-only fallback through an SSH tunnel:

```text
ssh -o ExitOnForwardFailure=yes -N -L 18025:127.0.0.1:18025 <operator>@<memoryos-host>
```

Use `https://memoryos-mail.72-62-193-33.nip.io` for normal browser access and `http://127.0.0.1:18025` only through the tunnel. Mailpit evidence proves Keycloak email generation and verification-link handling in staging; it does not prove public-domain deliverability, SPF, DKIM, DMARC, or provider retry behavior.

### Run the protected inspection tools

The repository-owned staging overlay replaces the active external pgweb runtime while preserving client `memoryos-pgweb`, role `memoryos_pgweb`, database `memoryos`, and public origin `https://memoryos-db.72-62-193-33.nip.io`. `postgres-inspector-bootstrap` idempotently sets `default_transaction_read_only=on`, grants current and future table/sequence `SELECT`, and writes a mode-`0600` pgpass file into a private volume. pgweb adds its own read-only guard, locks the database session, disables SSH, and bounds queries. Its raw port is private; only `memoryos-pgweb-oauth2-proxy` reaches the proxy network and loopback port `18026`.

Before the first staging start, run `infrastructure/inspection/provision-staging-secrets.sh`. Redis and the worker consume the same generated worker-password file; load only the pgweb/Redis Insight client secrets into the controlled Keycloak reconciliation shell. Redis passwords, Redis Insight encryption key, cookie secrets, and TLS material remain file-backed Compose secrets. The script preserves complete secret sets, warns 30 days before certificate expiry, supports explicit coordinated rotation through `MEMORYOS_REDIS_TLS_ROTATE=true`, refuses incomplete TLS material, and prints no secret values.

Redis starts with TLS, `default` disabled, a namespace-bounded worker ACL, and a separate read-only `memoryos-inspector` ACL. Redis Insight stores encrypted state, cannot add/edit/delete connections, and receives only the preconfigured TLS connection. Its raw port is private; only `memoryos-redisinsight-oauth2-proxy` reaches the proxy network and loopback port `18027`.

Both proxies require realm role `memoryos-inspector`. Verify realm-local `admin` can enter each tool and an ordinary MemoryOS user receives denial. Then prove pgweb rejects a write transaction and the Redis inspector rejects both `XADD` and `CONFIG`. Keep `/apps/memoryos-pgweb` stopped but intact for rollback until this acceptance passes.

Build immutable API and web images from the same reviewed commit and tag both with the full source SHA:

```text
docker build --build-arg VCS_REF=<40-character-commit> --build-arg BUILD_DATE=<UTC-timestamp> --tag memoryos-api:sha-<40-character-commit> .
docker build --file web/Dockerfile --build-arg VCS_REF=<40-character-commit> --build-arg BUILD_DATE=<UTC-timestamp> --tag memoryos-web:sha-<40-character-commit> .
```

Validate and start:

```text
docker compose \
  --env-file /apps/memoryos/.env.staging \
  -f infrastructure/deployment/compose.base.yaml \
  -f infrastructure/deployment/compose.staging.yaml \
  config --quiet

docker compose \
  --env-file /apps/memoryos/.env.staging \
  -f infrastructure/deployment/compose.base.yaml \
  -f infrastructure/deployment/compose.staging.yaml \
  up -d --wait
```

Only shared Keycloak, web, and the three OAuth2 Proxies join the external proxy network; Mailpit, pgweb, and Redis Insight remain on private backends. PostgreSQL binds to server loopback port `5556` by default; Keycloak, API, and web diagnostics default to `18180`, `18080`, and `18081`, while the inspection proxies also bind loopback ports `18026` and `18027`. Shared Keycloak keeps `orgmemory-keycloak`, `memoryos-keycloak`, and `keycloak` aliases while public issuers remain under `https://auth.kl3in.tech`.

For local database access:

```powershell
ssh -o ExitOnForwardFailure=yes -N -L 15555:127.0.0.1:5556 <operator>@<memoryos-host>
```

Migrating the retained MemoryOS and Keycloak databases from the legacy shared PostgreSQL deployment requires the backup-first [shared runtime migration runbook](shared-runtime-migration.md). Do not point writers at a fresh target or delete source data before its restore and rollback gates pass.

## MEM-28 Organization-only schema cutover

V4 drops the historical Workspace tables and columns. The SQL is intentionally a direct clean cutover, but the deployment remains destructive and is not backward compatible with the prior API image.

Before the first V4 staging deployment:

1. Stop API writers.
2. Use the existing PostgreSQL backup profile/runbook to capture the `memoryos` database dump, dump list, checksum manifest, Flyway history, and Organization/membership/invitation counts.
3. Verify the checksum and copy the archive off-host.
4. Restore the dump into an isolated rehearsal database.
5. Start the exact cutover API image against the restored copy and confirm V4 is the latest successful Flyway migration.
6. Confirm Organization, Organization-membership, Actor, binding, and invitation counts match the pre-cutover values; confirm `workspaces`, `workspace_memberships`, `default_workspace_id`, and `workspace_id` are absent.
7. Complete owner login, verify the Organization-only `/api/identity/me` response, accept one temporary member invitation, verify exactly one Organization `MEMBER` row, then clean up the temporary evidence.

Only after the restored-copy rehearsal passes may the same image migrate staging. Keep the verified archive and prior image until post-cutover approval. Rollback after V4 means stopping writers, restoring the pre-cutover dump, and restarting the prior image; do not run the prior binary against the V4 schema.

## MEM-24 Tenant schema cutover

V6 renames the active Organization schema to Tenant, preserves all business UUIDs, and adds the checked unique `tenants.deployment_slot = 1` invariant. This is a maintenance-window clean cutover; no compatibility views or dual runtime exist.

Before the first V6 deployment:

1. Stop API and worker writers.
2. Capture the `memoryos` custom-format dump, restore list, SHA-256 manifest, Flyway history, and Organization-era row counts; copy verified evidence off-host.
3. Restore into an isolated rehearsal database and run the exact cutover API image.
4. Confirm V7 is the latest successful migration; V6 preserves Tenant terminology, UUIDs, and row counts, while V7 adds only the scheduler control-plane table and indexes.
5. Confirm exactly one `deployment_slot = 1` Tenant exists and its UUID equals `MEMORYOS_TENANT_ID`.
6. Start the worker, complete owner login, call `/api/identity/me` with no `X-TenantId`, and repeat with a conflicting `X-TenantId`; both successful responses must project the configured Tenant and retain membership authorization.

Only after rehearsal passes may the same image migrate staging. Rollback after V6 means stopping writers, restoring the pre-cutover archive, and restarting the prior image. Never run an Organization-era binary against V6.

## Startup contract

Flyway creates identity and historical capability state, then V6 exposes Tenant tables, columns, constraints, and indexes. API startup transactionally creates or verifies:

- the configured `MEMORYOS_TENANT_ID`;
- the exact `(MEMORYOS_IDENTITY_ISSUER, MEMORYOS_INITIAL_OWNER_SUBJECT)` actor binding;
- one Tenant and one Tenant `OWNER` membership; and
- the deployment change reference.

The singleton bootstrap row serializes concurrent replicas, while `tenants.deployment_slot` prevents a second Tenant. Restart with identical configuration reuses the aggregate. Changed Tenant UUID, subject, names, slug, reference, statuses, cardinality, or memberships fails startup rather than mutating authority. Repair requires explicitly reviewed persistence recovery; never bypass drift with a temporary profile or convenience endpoint.

## Runtime checks

| Endpoint | Access | Expected result |
| --- | --- | --- |
| `GET /actuator/health` | Public | API health through the web gateway |
| `GET /api/identity/me` | Bound bearer JWT or authenticated browser session | Stable Actor plus nullable Tenant projection and capabilities |
| `GET http://127.0.0.1:8081/actuator/health/readiness` | Worker container/internal diagnostics | Worker datasource, Redis, and db-scheduler readiness |
| `GET /` | No browser session | Sign-in state with `/oauth2/authorization/memoryos` action |
| `GET /` | Initial owner after Keycloak login | Authenticated `New Session` application shell |
| `GET /access-not-provisioned` | Public browser route | Accessible `ACCESS_NOT_PROVISIONED` explanation |

Open `/oauth2/authorization/memoryos` to start browser login. Confirm the Keycloak request contains `code_challenge_method=S256`. After callback, confirm the session cookie changes, `/api/identity/me` returns the bootstrapped actor ID, refresh retains the authenticated shell, and an unprovisioned Keycloak account receives `ACCESS_NOT_PROVISIONED`. The browser shell does not display the raw actor UUID.

## Run the worker

Start API first so Flyway completes, then run the persistent worker with the same managed database values:

```powershell
infisical run --env=dev --projectId=<memoryos-project-id> -- .\gradlew.bat :worker:bootRun --no-daemon
```

The worker serves readiness on port `8081` by default. db-scheduler persistently owns the recurring Redis stream/group topology task; the current business executor still claims bounded index/cleanup batches directly from PostgreSQL until the single MEM-43/MEM-44/MEM-51 relay-consumer-cutover increment replaces it. In development, worker-owned Arconia Redis Dev Services uses fixed host port `56379`, while the worker connects to the API-owned PostgreSQL Dev Service on `55432`. Tests retain their isolated container contracts. Production requires explicit Redis endpoint, ACL, TLS, timeout/pool, and scheduler-name values. Stop the worker before the API so graceful scheduler shutdown completes while the shared database remains available.

API and worker enable Spring Boot virtual threads and JVM keep-alive by default. The db-scheduler execution executor creates one named virtual thread per control task; the scheduler's two execution slots, datasource pool, and Redis pool remain the resource bounds. Do not add a virtual-thread pool, duplicate semaphore around the datasource pool, or global carrier-pool tuning. Netty event loops and scheduler/datasource housekeeping remain platform threads by design. Diagnose production contention with JFR `jdk.VirtualThreadPinned` and `jdk.VirtualThreadSubmitFailed` events or a `jcmd <pid> Thread.dump_to_file -format=json <file>` dump before changing concurrency.

## Repository verification

```powershell
.\gradlew.bat clean check --no-daemon
cd web
pnpm check
pnpm test:e2e
```

The Gradle gate compiles all server modules, runs capability and integration tests, verifies Spring Modulith and ArchUnit boundaries, and starts both composition roots in tests. The frontend gate verifies generated-client and route-tree freshness, lint, formatting, TypeScript, unit behavior, and the production bundle; Playwright exercises signed-out, authenticated, and unprovisioned browser states against its own loopback Vite and backend processes, so a separately running development server is not reused.