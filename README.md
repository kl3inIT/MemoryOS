# MemoryOS

MemoryOS is a durable personal knowledge system built as a controlled Spring Modulith monolith. External provider identities resolve to stable internal actors; the current product path bootstraps one Organization and admits its configured owner through Keycloak browser login.

## Start here

- [Repository guide](AGENTS.md) — canonical navigation and workflow rules.
- [Architecture](ARCHITECTURE.md) — implemented system shape and runtime flows.
- [Vision](docs/vision.md) — product outcomes and principles.
- [Roadmap](docs/roadmap.md) — delivered and active increments.
- [Development runtime runbook](docs/runbooks/development-runtime.md) — runtime configuration and verification.

Claude Code reads the same repository guide through [`CLAUDE.md`](CLAUDE.md); project rules are not duplicated.

## Requirements

- JDK 25.
- Checked-in Gradle wrapper; no system Gradle installation.
- Docker with the Compose plugin for the production API container.

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

The gate compiles all modules, runs capability and HTTP integration tests, verifies Spring Modulith and ArchUnit boundaries, and starts both composition roots in tests.

The production image is built from [`Dockerfile`](Dockerfile); [`infrastructure/deployment/compose.production.yaml`](infrastructure/deployment/compose.production.yaml) runs the exact image on existing shared networks. Deployment commands and required configuration are in the runtime runbook.

## Current runtime behavior

API startup runs Flyway, transactionally bootstraps or verifies the configured initial Organization owner, and fails on configuration or aggregate drift. The API supports stateless bearer authentication under `/api/**` and confidential OAuth2 Authorization Code + PKCE browser login backed by Spring Session JDBC.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | API health |
| `GET /api/identity/me` | Valid JWT with exact stored `(issuer, subject)` binding | `{"actorId":"<uuid>"}` |
| `GET /api/identity/me` | Missing/invalid token or unknown binding | `401` |
| `GET /` | Bound browser identity with active Organization membership | `{"actorId":"<uuid>"}` |
| `GET /access-not-provisioned` | Public failure state | `403` with `ACCESS_NOT_PROVISIONED` |

The [identity contract](docs/specs/identity.md), [organization contract](docs/specs/organization.md), and [runtime runbook](docs/runbooks/development-runtime.md) define the write boundary and operational procedure.

## Engineering policies

- [Engineering conventions](docs/conventions.md)
- [Repository operating model](docs/guidelines/operating-model.md)
- [Production-first persistence](docs/guidelines/persistence.md)
- [Testing and verification](docs/guidelines/testing.md)
- [ADR 0003: evidence-driven audit boundary](docs/decisions/0003-defer-audit-until-evidence-consumer.md)

The legacy OrgMemory repository is reference-only. Do not copy its structure or infrastructure breadth without a current MemoryOS capability requirement.