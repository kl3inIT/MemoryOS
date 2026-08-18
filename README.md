# MemoryOS

MemoryOS is a durable personal knowledge system built as a controlled Spring Modulith monolith. External provider identities resolve to stable internal actors before knowledge ownership is introduced.

## Start here

- [Repository guide](AGENTS.md) — canonical navigation and workflow rules.
- [Architecture](ARCHITECTURE.md) — implemented system shape and runtime flows.
- [Vision](docs/vision.md) — product outcomes and principles.
- [Roadmap](docs/roadmap.md) — delivered and active increments.
- [Development runtime runbook](docs/runbooks/development-runtime.md) — API, worker, Keycloak, PostgreSQL, and verification procedures.

Claude Code reads the same canonical repository guide through [`CLAUDE.md`](CLAUDE.md); project rules are not duplicated.

## Requirements

- JDK 25.
- No system Gradle installation; use the checked-in wrapper.

## Modules

| Module | Responsibility |
| --- | --- |
| `core` | Capability contracts, model, capability-owned persistence, and architecture rules |
| `api` | Spring Boot HTTP composition root |
| `worker` | Spring Boot background-processing composition root |

The current capabilities are `identity`, `authorization`, `knowledge`, `ingestion`, `retrieval`, `assistant`, and `audit`. See [ARCHITECTURE.md](ARCHITECTURE.md) for dependency boundaries.

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

## Current runtime behavior

The API is a stateless OAuth2 Resource Server backed by PostgreSQL actor bindings.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | API health |
| `GET /api/identity/me` | Valid JWT with exact stored `(issuer, subject)` binding | `{"actorId":"<uuid>"}` |
| `GET /api/identity/me` | Missing/invalid token or unknown binding | `401` |

The current identity write boundary is defined in the [identity capability contract](docs/specs/identity.md); approved bootstrap and recovery procedures live in the [runtime runbook](docs/runbooks/development-runtime.md).

## Engineering policies

- [Engineering conventions](docs/conventions.md)
- [Repository operating model](docs/guidelines/operating-model.md)
- [Production-first persistence](docs/guidelines/persistence.md)
- [Testing and verification](docs/guidelines/testing.md)
- [Identity capability contract](docs/specs/identity.md)

The legacy OrgMemory repository is reference-only. Do not copy its structure or infrastructure breadth without a current MemoryOS capability requirement.
