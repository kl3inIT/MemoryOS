# MEM-52 verification

Verified locally on 2026-09-03 from the active MEM-52 branch.

## Repository gates

- `./gradlew.bat clean check --no-daemon` — passed; 23 actionable tasks, including API, worker, capability architecture, PostgreSQL migration, MinIO, Redis, and composition-root tests.
- `pnpm check` in `web/` — passed; generated API stability, Playwright image pin, lint, formatting, TypeScript, 44 unit tests, route generation, production bundle, and emitted-font checks.
- `git diff --check` — passed. Git reported only the existing Windows working-tree line-ending conversion notice for `web/Dockerfile`.
- JetBrains MCP inspected all 111 non-deleted changed files with `errorsOnly=false`; the five deleted paths were excluded. Actionable findings were fixed and re-inspected clean. Retained findings are inspection noise or required conventions: generated Hey API locals, Java 25 `public static void main` launch signatures, intentional custom HTTP headers/loopback HTTP, repository-standard compact Markdown tables, and unresolved SQL symbols where IntelliJ has no configured data source. PostgreSQL/H2 migration tests remain authoritative for the SQL.

## Object storage and lifecycle

- `S3ObjectStorageIntegrationTest` passed against `minio/minio:RELEASE.2025-04-22T22-12-26Z`: exact signed headers, altered-checksum rejection, authorization expiry, metadata inspection, streaming reads, sentinel probing, configured-origin CORS, untrusted-origin rejection, and idempotent delete.
- `ObjectUploadLifecycleIntegrationTest` passed against PostgreSQL: wrong-Tenant lookup, integrity mismatch/retry, stable provider-unavailable mapping, adoption replay rejection, adopted retention, expired pre-adoption cleanup, transient deletion recovery, and verification/cleanup fencing.
- `PostgresSourceLifecycleTest` passed: duplicate convergence, finalized-receipt replay, discarded-object reaping, adopted-object removal, and restrictive-FK cleanup.
- `SourceApiIntegrationTest`, `BearerAuthenticationIntegrationTest`, and `OpenApiContractTest` passed for OWNER authorization, initiate/finalize JSON commands, validation, and the committed browser contract.

## Runtime surfaces

- `WorkerFileProcessingIntegrationTest.redisStreamsIndexRemoveAndDeleteOneRealFile` passed with real PostgreSQL, Redis Streams, MinIO, the S3 adapter, Tika child extraction, indexing, item removal, source deletion, provider-object absence, and relational object/upload cleanup.
- The FILE Source Playwright scenario passed in Chromium. Network assertions observed one PUT to `https://objects.example.test`, exact checksum/content-type headers, no file bytes in any `/api/sources/**` body, a failed finalize response, and successful finalize retry without a second PUT.
- `direct-upload.test.ts` covered browser SHA-256, required-header transport, progress, provider rejection, and cancellation.
- Production `api`, `worker`, and `web` images built successfully. A running production web image returned `200` with `connect-src 'self' http://localhost:19000` substituted into its CSP.

## Deployment

- `docker compose -f infrastructure/deployment/compose.base.yaml config -q` passed with required deployment values.
- `infrastructure/minio/bootstrap.sh` ran twice against the pinned MinIO/`mc` images and a durable volume. Both runs completed successfully, kept the bucket private, reconciled users/policies/sentinel, used `/tmp` for `mc` state under a read-only root filesystem, and authenticated the distinct API and worker identities by reading the sentinel.
- `infrastructure/minio/provision-secrets.sh` created a complete mode-`0600` secret set and replayed idempotently.
- `sh -n` passed for the MinIO bootstrap/provision scripts and application launcher.

## Cutover boundary

V9 is intentionally destructive for the early-project environment: it removes `content_bytes` and requires a reset/empty application database. Rollback is whole-release restore of the pre-V9 PostgreSQL backup and pre-cutover images. No BYTEA fallback, dual-write, reverse migration, or mixed-version rolling path exists.
