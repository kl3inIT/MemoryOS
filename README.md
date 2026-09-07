# MemoryOS

MemoryOS is a durable personal knowledge system built as a controlled Spring Modulith monolith. External provider identities resolve to stable internal actors; each self-hosted deployment bootstraps one fixed Tenant and admits its configured owner through Keycloak browser login.

## Start here

- [Repository guide](AGENTS.md) — canonical navigation and workflow rules.
- [Architecture](ARCHITECTURE.md) — implemented system shape and runtime flows.
- [Vision](docs/vision.md) — product outcomes and principles.
- [Roadmap](docs/roadmap.md) — delivered and active increments.
- [Development runtime runbook](docs/runbooks/development-runtime.md) — runtime configuration and verification.
- [Shared PostgreSQL and Keycloak migration runbook](docs/runbooks/shared-runtime-migration.md) — backup, restore, cutover, rollback, and shared-realm verification.

Claude Code reads the same repository guide through [`CLAUDE.md`](CLAUDE.md); project rules are not duplicated.

## Requirements

- JDK 25.
- Checked-in Gradle wrapper; no system Gradle installation.
- Node.js 24 with Corepack; `web/package.json` pins pnpm.
- Docker with the Compose plugin for the hardened PostgreSQL, private MinIO, shared Keycloak, API, indexing worker, and web deployment stack.

## Modules and capabilities

| Module | Responsibility |
| --- | --- |
| `core` | Seven closed capability implementations, transactions, persistence, the provider-neutral object-storage contract, and its S3 adapter |
| `connector` | Shared provider bundle: Google acquisition, offline Sheets/Docs snapshots, Java XLSX/CSV readers, Docling PDF/DOCX/PPTX, and bounded Tika TXT/Markdown |
| `api` | Spring Boot HTTP, validation, migration, and security composition root |
| `worker` | PostgreSQL-authoritative source synchronization, indexing, and cleanup over Redis Streams |

Current core capabilities are `identity`, `tenant`, `invitation`, `objectstorage`, `connector`, `document`, and `ingestion`. Provider implementations remain outside capability packages under `connector/src/main/java/io/memoryos/provider/<provider>` except the capability-owned S3 storage adapter under `objectstorage.s3`. See [ARCHITECTURE.md](ARCHITECTURE.md) for enforced dependencies.

## Build and verify

Windows:

```powershell
.\gradlew.bat clean check --no-daemon
```

Linux or macOS:

```bash
./gradlew clean check --no-daemon
```

Frontend:

```powershell
corepack enable
cd web
pnpm install --frozen-lockfile
pnpm check
pnpm test:e2e
```

The Gradle gate compiles all server modules, runs capability and HTTP integration tests, verifies Spring Modulith and ArchUnit boundaries, and starts both composition roots in tests. The frontend gate regenerates the OpenAPI client, rejects generated drift, lints without product-source warnings, checks formatting and TypeScript, runs focused tests, and creates the production bundle; Playwright exercises the observable browser states.

## Refresh the generated API contract

Spring controllers and Spring-visible request/response metadata own the browser API contract. The committed `openapi.yml` is generated from a full API test context and the web client is generated from that snapshot.

Windows:

```powershell
$env:MEMORYOS_OPENAPI_WRITE = "true"
.\gradlew.bat :api:test --tests "*OpenApiContractTest*"
Remove-Item Env:MEMORYOS_OPENAPI_WRITE
cd web
pnpm generate:api
```

Run the OpenAPI contract test again without the write flag, then run `pnpm check`. Normal runtime configuration does not expose springdoc API-doc endpoints.

The API and worker images are built from [`Dockerfile`](Dockerfile) and inject the explicitly selected Infisical environment before Spring Boot starts; the browser image is built from [`web/Dockerfile`](web/Dockerfile). Deployment is composed explicitly from [`compose.base.yaml`](infrastructure/deployment/compose.base.yaml) plus a staging or production overlay. The base owns PostgreSQL, private MinIO and its idempotent bucket/policy bootstrap, the Keycloak runtime shared with OrgMemory, API, worker, and web. Distinct file-mounted MinIO credentials constrain API to signed raw PUT/inspection and worker to raw/extracted read, write, and delete; both probe one private sentinel. Staging adds Mailpit, TLS Redis, read-only pgweb and Redis Insight, SSO proxies, and an owner-only bucket-read-only MinIO Console using native Keycloak OIDC; production adds no inspection exposure. Developer `bootRun` processes use Arconia's `development` profile for PostgreSQL/Redis but require an explicitly configured object-storage endpoint and credentials.

The staging application is available at `https://memoryos.72-62-193-33.nip.io`; Keycloak retains the matching exact HTTPS callback and `/invite/activate` action return for `memoryos-web`. The configured object-storage origin routes directly to MinIO port `9000` and must match the presigning endpoint, MinIO CORS allowlist, and web CSP. The staging-only Console uses `https://memoryos-minio.72-62-193-33.nip.io`, exact `/oauth_callback`, and the owner-only `memoryos-inspector` policy without exposing port `9001` directly.

## Current runtime behavior

API startup runs Flyway through V20 and transactionally bootstraps or verifies the configured Tenant and initial owner. FILE retains checksum-bound browser-direct PUT and API finalization. Google Drive credentials are authorized independently and reusable across multiple Sources, each with a GENERAL My Drive scope or SPECIFIC file/folder selection. Durable SOURCE_SYNC acquires tracked immutable snapshots before ordinary INGESTION reads them from MinIO. The worker dispatches identifiers through Redis, renews PostgreSQL leases, and atomically publishes the current Document/artifact only while its input, source, selection, credential authority, and claim remain current. Google sources remain RESTRICTED and excluded from FILE PUBLIC document access; document ACLs, reader linking, and a document viewer are not implemented.

For Google acquisition, the owner uploads or pastes a downloaded Google **Web application OAuth client JSON** of at most 16 KiB UTF-8. Enable the Drive, Sheets, and Docs APIs in that app's project and configure its consent screen. There is no server-wide default Google client ID/secret. Supply `MEMORYOS_GOOGLE_DRIVE_CREDENTIAL_ENCRYPTION_KEY` (base64-encoded 32-byte AES key) and `MEMORYOS_GOOGLE_DRIVE_CREDENTIAL_KEY_VERSION` consistently to API and worker. API additionally requires `MEMORYOS_GOOGLE_DRIVE_REDIRECT_URI`, registered exactly in the uploaded app at the browser origin's `/login/oauth2/code/google-drive`; this does not replace Keycloak login. V16 requires legacy connections to reauthorize with an owner app while retaining their Source, roots and content. Keep credentials outside the repository. See the [Connector contract](docs/specs/connector.md) for authorization and revision semantics.

Google setup follows Credential → Connector: select an existing credential or authorize a new one, then name the Source and choose its scope. **Specific**, the default, accepts 1–20 file/folder links and includes selected-folder descendants. **General** synchronizes the connected OAuth account's My Drive tree, not Shared with me, Shared Drives, or other users' drives. Both use the same durable pipeline. Reusing a credential requires no additional JSON upload or Google consent. OAuth app/redirect configuration is available under the collapsed Setup instructions, not a separate Source setup field. V18 preserves existing credential identities; V20 keeps existing Sources Specific without changing their roots, interval or indexed content. Reconnect/revoke affects all Sources attached to the credential, while deleting a Source retains it.

Owners configure each Google Source under **Synchronization → Automatic interval → Edit interval**. The default is 5 minutes, with a minimum of 1 whole minute. Saving changes future scheduling without interrupting current work or changing the saved scope; Synchronize now remains available. See the [Connector contract](docs/specs/connector.md) for concurrency and authorization semantics.

Binary inputs remain capped at 10 MiB; native snapshots have a separate 32 MiB bound and explicit structural/request/time limits. `INDEXED` means a current extraction artifact, not chunking, embedding, or search readiness. For real Docling coverage in the Gradle worker integration suite, set `DOCLING_TEST_ENDPOINT` to a reachable pinned Docling service before running the normal gate.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | API health |
| `GET /api/identity/me` | Bound bearer JWT or authenticated browser session | Stable actor plus nullable Tenant context and capabilities |
| `GET /api/identity/me` | Missing/invalid authentication or unknown binding | `401` |
| `GET /` | Browser origin | MemoryOS application; resolves session through `/api/identity/me` |
| `GET /access-not-provisioned` | Browser origin | Accessible denial state without account creation |
| `GET /invite/activate` | Public Keycloak action return | Starts browser OAuth2 login without carrying invitation correlation |
| `/api/credentials/google-drive/**` | Active Tenant owner | List, authorize, reconnect, revoke and delete unused reusable credentials |
| `/api/sources/**` | Active Tenant owner | FILE uploads; Google Source creation with an existing credential, General/Specific scope and sync; shared list/detail, reindex, remove and delete commands |
| `/api/source-operations/**` | Active Tenant owner | Poll durable ingestion, source-sync and cleanup operations |

The [identity](docs/specs/identity.md), [tenant](docs/specs/tenant.md), [invitation](docs/specs/invitation.md), [object storage](docs/specs/object-storage.md), [connector](docs/specs/connector.md), [document](docs/specs/document.md), and [ingestion](docs/specs/ingestion.md) contracts define the implemented capability boundaries.

## Engineering policies

- [Engineering conventions](docs/conventions.md)
- [Repository operating model](docs/guidelines/operating-model.md)
- [Production-first persistence](docs/guidelines/persistence.md)
- [Testing and verification](docs/guidelines/testing.md)
- [ADR 0003: evidence-driven audit boundary](docs/decisions/0003-defer-audit-until-evidence-consumer.md)

The legacy OrgMemory repository is reference-only. Do not copy its structure or infrastructure breadth without a current MemoryOS capability requirement.
