# Persistent local development services

## Problem

Arconia Dev Services gives direct API/Worker launches disposable Testcontainers with random Docker names. Restarting the API removes its PostgreSQL and Redis dependencies, obscuring the local runtime and discarding the developer's working state.

## Decision

`infrastructure/deployment/compose.development.yaml` owns exactly one local PostgreSQL and Redis pair. It has stable `memoryos-development-*` container and volume names, binds only to `127.0.0.1:55432` and `127.0.0.1:56379`, and preserves its state across application and Docker restarts. API and Worker retain the `development` Spring profile but explicitly disable Arconia PostgreSQL/Redis Dev Services and use the existing local connection defaults.

The composition is developer-local only. Its fixed `arconia` credentials are not staging credentials and must not be used outside loopback development. Resetting state requires the explicit destructive `docker compose ... down -v` command; ordinary `up`, `stop`, and application restarts retain it.

## Scope and boundaries

- Keep API, Worker, Vite and tests as direct processes; no application-container Compose stack.
- Keep test-owned Testcontainers isolated and unchanged.
- Leave configured MinIO and Docling endpoints unchanged. Docling is remote or independently started; it is not a direct-runtime dependency that this composition creates.
- Replace the current Dev Services lifecycle documentation and local inspection wording.

## Failure and recovery

If the composition is absent or unhealthy, API/Worker startup fails against the loopback dependency instead of silently creating an alternate container. Start it with `docker compose -f infrastructure/deployment/compose.development.yaml up -d --wait`. Stop direct Worker before API; neither stop tears down PostgreSQL or Redis. Use `down -v` only when a fresh local database is intended.

## Verification

Validate Compose configuration, create the named services, prove their health and fixed loopback bindings, then start the direct API and Worker against them. Run focused API configuration/build checks and record observed lifecycle evidence in `verification.md`.
