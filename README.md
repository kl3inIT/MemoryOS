# MemoryOS

MemoryOS starts as a controlled Spring Modulith monolith with separate API and worker deployables. The legacy OrgMemory repository is reference-only.

## Requirements

- JDK 25
- No system Gradle installation; use the checked-in Gradle wrapper

## Modules

| Module | Responsibility |
| --- | --- |
| `core` | Seven capability modules and their architecture rules |
| `api` | Spring Boot HTTP composition root and health endpoint |
| `worker` | Spring Boot background-processing composition root |

The core capabilities are `identity`, `authorization`, `knowledge`, `ingestion`, `retrieval`, `assistant`, and `audit`. Public contracts live at each capability root. Capability-owned persistence lives under that capability's `persistence` package and is not shared across capability boundaries.

## Build and test

Windows:

```powershell
.\gradlew.bat clean check
```

Linux or macOS:

```bash
./gradlew clean check
```

The `check` task compiles all modules, runs Spring Modulith verification, enforces ArchUnit dependency rules, and runs application context smoke tests.

## Run the API

```powershell
.\gradlew.bat :api:bootRun
```

Health check: `GET http://localhost:8080/actuator/health`

## Run the worker

```powershell
.\gradlew.bat :worker:bootRun
```

The foundation worker starts without a scheduler or job processor and exits cleanly. A durable processing loop will be introduced with the first worker-owned vertical slice.

## Scope

This foundation has no database, OpenFGA client, model provider, connector, MCP server, GraphRAG engine, or production deployment configuration. See [ADR 0001](docs/decisions/0001-controlled-modular-monolith.md) for the architecture decision.
