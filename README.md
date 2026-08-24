# MemoryOS

MemoryOS is a durable personal knowledge system built as a controlled Spring Modulith monolith. External provider identities resolve to stable internal actors; the current product path bootstraps one Organization and admits its configured owner through Keycloak browser login.

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
- Docker with the Compose plugin for the PostgreSQL, shared Keycloak, API, and web production stack.

## Modules and capabilities

| Module | Responsibility |
| --- | --- |
| `core` | Capability contracts, behavior, transactions, persistence, and architecture rules |
| `api` | Spring Boot HTTP and security composition root |
| `worker` | Spring Boot background-processing composition root |

Current capabilities: `identity`, `organization`, `authorization`, `knowledge`, `ingestion`, `retrieval`, and `assistant`. See [ARCHITECTURE.md](ARCHITECTURE.md) for enforced dependencies.

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

The API image is built from [`Dockerfile`](Dockerfile); the browser image is built from [`web/Dockerfile`](web/Dockerfile). [`infrastructure/deployment/compose.production.yaml`](infrastructure/deployment/compose.production.yaml) owns MemoryOS PostgreSQL, the single Keycloak runtime shared with OrgMemory, API, and web. PostgreSQL keeps isolated `memoryos` and `keycloak` databases; MemoryOS repository provisions only the `memoryos` realm. Deployment commands and migration/rollback procedures are in the runtime runbooks.

## Current runtime behavior

API startup runs Flyway, transactionally bootstraps or verifies the configured initial Organization owner, and fails on configuration or aggregate drift. Remaining `/api/**` routes use stateless bearer authentication. The exact current-identity endpoint also accepts an existing confidential OAuth2 Authorization Code + PKCE browser session backed by Spring Session JDBC.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | API health |
| `GET /api/identity/me` | Bound bearer JWT or authenticated browser session | `{"actorId":"<uuid>"}` |
| `GET /api/identity/me` | Missing/invalid authentication or unknown binding | `401` |
| `GET /` | Browser origin | MemoryOS application; resolves session through `/api/identity/me` |
| `GET /access-not-provisioned` | Browser origin | Accessible denial state without account creation |

The [identity contract](docs/specs/identity.md), [organization contract](docs/specs/organization.md), and [runtime runbook](docs/runbooks/development-runtime.md) define the write boundary and operational procedure.

## Engineering policies

- [Engineering conventions](docs/conventions.md)
- [Repository operating model](docs/guidelines/operating-model.md)
- [Production-first persistence](docs/guidelines/persistence.md)
- [Testing and verification](docs/guidelines/testing.md)
- [ADR 0003: evidence-driven audit boundary](docs/decisions/0003-defer-audit-until-evidence-consumer.md)

The legacy OrgMemory repository is reference-only. Do not copy its structure or infrastructure breadth without a current MemoryOS capability requirement.