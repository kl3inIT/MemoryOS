# MemoryOS

MemoryOS is a durable personal knowledge system built as a controlled Spring Modulith monolith. External provider
identities resolve to stable internal actors; each self-hosted deployment bootstraps one fixed Tenant and admits its
configured owner through Keycloak browser login.

Chat is the application home, with private conversation history, streamed answers, Stop and reconnect. Documents come
from FILE uploads, Google Drive and SharePoint Sources; direct Search stays available at `/search`.

## Start here

- [Repository guide](AGENTS.md) — canonical navigation and workflow rules.
- [Architecture](ARCHITECTURE.md) — implemented system shape, runtime flows and data ownership.
- [Vision](docs/vision.md) — product outcomes and principles.
- [Roadmap](docs/roadmap.md) — delivered and active increments.
- [Development runtime runbook](docs/runbooks/development-runtime.md) — environment variables, local runs and staging.
- [Shared PostgreSQL and Keycloak migration runbook](docs/runbooks/shared-runtime-migration.md) — backup, restore,
  cutover, rollback and shared-realm verification.

Capability contracts live under [docs/specs](docs/specs): [chat](docs/specs/chat.md),
[identity](docs/specs/identity.md), [tenant](docs/specs/tenant.md), [invitation](docs/specs/invitation.md),
[connector](docs/specs/connector.md), [document](docs/specs/document.md), [ingestion](docs/specs/ingestion.md),
[search](docs/specs/search.md) and [object storage](docs/specs/object-storage.md). Each one, not this file, is the
authority for its behavior and its API surface.

Claude Code reads the same repository guide through [`CLAUDE.md`](CLAUDE.md); project rules are not duplicated.

## Requirements

- JDK 25.
- The checked-in Gradle wrapper; no system Gradle installation.
- Node.js 24 with Corepack; `web/package.json` and `landing/package.json` pin pnpm.
- Docker with the Compose plugin for PostgreSQL, private MinIO, shared Keycloak, API, worker and web.

## Modules

| Module | Responsibility |
| --- | --- |
| `core` | Capability implementations: `iam`, `objectstorage`, `connector`, `document`, `ingestion`, `retrieval`, `chat` and `mcp`. IAM owns identity, Tenant membership, invitations, Users, Groups and authorization |
| `connector` | Shared provider bundle: Google and SharePoint acquisition, offline Sheets/Docs snapshots, XLSX/CSV readers, Docling PDF/DOCX/PPTX and bounded Tika TXT/Markdown |
| `api` | Spring Boot HTTP, validation, migration and security composition root; Chat runs here on virtual threads |
| `worker` | Source synchronization, extraction, Search projection and cleanup over Redis Streams |
| `interpreter` | The `run_python` service and its disposable executors ([how it works](interpreter/HOW_IT_WORKS.md)) |
| `web`, `landing` | The browser application and the public landing page |

[ARCHITECTURE.md](ARCHITECTURE.md) records the enforced dependencies and where each kind of state lives.

## Build and verify

`clean check` is the repository-wide gate. Windows:

```powershell
.\gradlew.bat clean check --no-daemon
```

Linux or macOS:

```bash
./gradlew clean check --no-daemon
```

The browser application:

```powershell
corepack enable
cd web
pnpm install --frozen-lockfile
pnpm check
pnpm test:e2e
```

The Gradle gate compiles every server module, runs capability and HTTP integration tests, verifies the Spring Modulith
and ArchUnit boundaries and starts both composition roots. The frontend gate regenerates the OpenAPI client, rejects
generated drift, lints, checks formatting and TypeScript, runs unit tests and builds the production bundle; Playwright
exercises the observable browser states.

The landing page (`https://vadan.app`, deployed separately; see the [landing runbook](docs/runbooks/landing.md)):

```powershell
pnpm --dir landing install --frozen-lockfile
pnpm --dir landing check
```

## Refresh the generated API contract

Spring controllers and their request/response metadata own the browser API contract: `openapi.yml` is generated from a
full API test context, and the web client is generated from that snapshot.

```powershell
$env:MEMORYOS_OPENAPI_WRITE = "true"
.\gradlew.bat :api:test --tests "*OpenApiContractTest*"
Remove-Item Env:MEMORYOS_OPENAPI_WRITE
cd web
pnpm generate:api
```

Run the contract test again without the write flag, then `pnpm check`. Runtime configuration exposes no springdoc
endpoints.

## Deployment

API and worker images build from [`Dockerfile`](Dockerfile) and inject the selected Infisical environment before Spring
Boot starts; the browser image builds from [`web/Dockerfile`](web/Dockerfile). A deployment is composed from
[`compose.base.yaml`](infrastructure/deployment/compose.base.yaml) plus a staging or production overlay: the base owns
PostgreSQL, private MinIO with its bucket bootstrap, the Keycloak runtime shared with OrgMemory, API, worker and web.
API and worker hold distinct file-mounted MinIO credentials. Staging adds Mailpit, TLS Redis, read-only pgweb and Redis
Insight behind SSO proxies and an owner-only MinIO Console; production adds no inspection surface.

Staging runs at `https://memoryos.72-62-193-33.nip.io`. The configured object-storage origin routes directly to MinIO
port `9000` and must match the presigning endpoint, the MinIO CORS allowlist and the web CSP. The
[CI and staging runbook](docs/runbooks/ci-cd.md) owns the delivery pipeline, and the
[development runtime runbook](docs/runbooks/development-runtime.md) owns every environment variable, including the
per-provider credential keys for Google Drive, SharePoint and MCP.

**Databases are not interchangeable.** API startup runs every Flyway migration in order and never repairs history. A
database carrying divergent branch-only migrations must not be started against this layout until a deliberate,
data-preserving reconciliation; use a fresh database for verification, and deploy API, worker and schema together. V14
invalidates existing Spring Sessions, so browser users sign in again after that transition. See
[data ownership and consistency](ARCHITECTURE.md#data-ownership-and-consistency).

## Engineering policies

- [Engineering conventions](docs/conventions.md)
- [Repository operating model](docs/guidelines/operating-model.md)
- [Production-first persistence](docs/guidelines/persistence.md)
- [Testing and verification](docs/guidelines/testing.md)
- [CI and staging delivery](docs/runbooks/ci-cd.md)
- [ADR 0003: evidence-driven audit boundary](docs/decisions/0003-defer-audit-until-evidence-consumer.md)

Project skills live in `.skills/`; the entries under `.agents/skills/`, `.claude/skills/` and `.omp/skills/` are
committed symbolic links to them, so edit the canonical files. On Windows, enable Developer Mode or use an elevated
terminal and clone with `git -c core.symlinks=true clone <repository-url>`.

The legacy OrgMemory repository is reference-only. Do not copy its structure or infrastructure breadth without a
current MemoryOS capability requirement.
