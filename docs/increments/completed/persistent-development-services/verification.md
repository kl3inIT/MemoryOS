# Persistent local development services verification

Local verification on Windows, 2026-10-01:

- `docker compose -f infrastructure/deployment/compose.development.yaml config --quiet` completed successfully.
- `docker compose -f infrastructure/deployment/compose.development.yaml up -d --wait` created `memoryos-development-postgres` and `memoryos-development-redis`; both reported healthy and bound only to `127.0.0.1:55432` and `127.0.0.1:56379`.
- The direct API started with `ARCONIA_BOOTSTRAP_MODE=dev` against those services and returned `{"groups":["liveness","readiness"],"status":"UP"}` from `GET /actuator/health`.
- The direct Worker started against the same services and reported `Started MemoryOsWorkerApplication`.
- A temporary PostgreSQL probe row survived `docker compose ... restart`; it was then removed. API health remained `UP` after the dependency restart.
- Removed four stopped random PostgreSQL/Redis Testcontainers. Named MinIO and Docling containers were left untouched because they are independently managed development endpoints and may retain data.

This verifies the named persistent development dependencies and direct-process connectivity. It does not claim local Docling, MinIO, live identity login, provider access, or document ingestion acceptance.
