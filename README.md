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
| `connector` | Shared provider adapter bundle; FILE uses Docling for PDF/DOCX/PPTX and Apache Tika 4 for TXT/Markdown |
| `api` | Spring Boot HTTP, validation, migration, and security composition root |
| `worker` | Persistence-backed indexing and cleanup composition root |

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

The API and worker images are built from [`Dockerfile`](Dockerfile) and inject the explicitly selected Infisical environment before Spring Boot starts; the browser image is built from [`web/Dockerfile`](web/Dockerfile). Deployment is composed explicitly from [`compose.base.yaml`](infrastructure/deployment/compose.base.yaml) plus a staging or production overlay. The base owns PostgreSQL, private MinIO and its idempotent bucket/policy bootstrap, the Keycloak runtime shared with OrgMemory, API, worker, and web. Distinct file-mounted MinIO credentials constrain API to signed PUT/inspection and worker to read/delete; both probe one private sentinel. Staging adds Mailpit, TLS Redis, read-only pgweb and Redis Insight, SSO proxies, and an owner-only bucket-read-only MinIO Console using native Keycloak OIDC; production adds no inspection exposure. Developer `bootRun` processes use Arconia's `development` profile for PostgreSQL/Redis but require an explicitly configured object-storage endpoint and credentials.

The staging application is available at `https://memoryos.72-62-193-33.nip.io`; Keycloak retains the matching exact HTTPS callback and `/invite/activate` action return for `memoryos-web`. The configured object-storage origin routes directly to MinIO port `9000` and must match the presigning endpoint, MinIO CORS allowlist, and web CSP. The staging-only Console uses `https://memoryos-minio.72-62-193-33.nip.io`, exact `/oauth_callback`, and the owner-only `memoryos-inspector` policy without exposing port `9001` directly.

## Current runtime behavior

API startup runs Flyway through V9, transactionally bootstraps or verifies the configured Tenant UUID and initial owner, and binds Arconia Web fixed Tenant context around HTTP requests. Source upload initiation returns a checksum-bound presigned PUT; finalization verifies MinIO metadata and adopts or discards the staged object. The worker starts after migrated API health, claims durable leased work, carries each work record's explicit `TenantId` through JDBC predicates, streams immutable object content through the FILE/Tika adapter for bounded detection/extraction, and token-guardedly publishes or cleans Document and object state.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | API health |
| `GET /api/identity/me` | Bound bearer JWT or authenticated browser session | Stable actor plus nullable Tenant context and capabilities |
| `GET /api/identity/me` | Missing/invalid authentication or unknown binding | `401` |
| `GET /` | Browser origin | MemoryOS application; resolves session through `/api/identity/me` |
| `GET /access-not-provisioned` | Browser origin | Accessible denial state without account creation |
| `GET /invite/activate` | Public Keycloak action return | Starts browser OAuth2 login without carrying invitation correlation |
| `/api/sources/**` | Active Tenant owner | Create/list/detail, initiate/finalize browser-direct FILE upload, reindex, remove, and delete; mutations use POST commands |
| `/api/source-operations/**` | Active Tenant owner | Poll durable index and cleanup operations |

The [identity](docs/specs/identity.md), [tenant](docs/specs/tenant.md), [invitation](docs/specs/invitation.md), [object storage](docs/specs/object-storage.md), [connector](docs/specs/connector.md), [document](docs/specs/document.md), and [ingestion](docs/specs/ingestion.md) contracts define the implemented capability boundaries.

## Engineering policies

- [Engineering conventions](docs/conventions.md)
- [Repository operating model](docs/guidelines/operating-model.md)
- [Production-first persistence](docs/guidelines/persistence.md)
- [Testing and verification](docs/guidelines/testing.md)
- [ADR 0003: evidence-driven audit boundary](docs/decisions/0003-defer-audit-until-evidence-consumer.md)

The legacy OrgMemory repository is reference-only. Do not copy its structure or infrastructure breadth without a current MemoryOS capability requirement.
