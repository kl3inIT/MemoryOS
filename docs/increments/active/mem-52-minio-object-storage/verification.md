# MEM-52 verification

Verified locally on 2026-09-03 from the active MEM-52 branch.

## Repository gates

- `./gradlew.bat clean check --no-daemon` — passed; 23 actionable tasks, including API, worker, capability architecture, PostgreSQL migration, MinIO, Redis, and composition-root tests.
- `pnpm check` in `web/` — passed; generated API stability, Playwright image pin, lint, formatting, TypeScript, 44 unit tests, route generation, production bundle, and emitted-font checks.
- `git diff --check` — passed.
- Before publication, JetBrains MCP inspected all 111 non-deleted changed files with `errorsOnly=false`; the five deleted paths were excluded. Actionable findings were fixed and re-inspected clean. Retained findings are inspection noise or required conventions: generated Hey API locals, Java 25 `public static void main` launch signatures, intentional custom HTTP headers/loopback HTTP, repository-standard compact Markdown tables, and unresolved SQL symbols where IntelliJ has no configured data source. PostgreSQL/H2 migration tests remain authoritative for the SQL.
- Post-review JetBrains inspection was attempted for all 23 non-deleted CodeRabbit-fix paths, but the mounted route repeatedly timed out. The required fallback passed instead: focused compilation/tests plus the terminating `clean check`, application contexts, OpenAPI regeneration/contract comparison, frontend lint/type/build, shell syntax, and Chromium runtime scenario. No IDE-clean claim is made for the post-review round.

## Object storage and lifecycle

- `S3ObjectStorageIntegrationTest` passed against digest-pinned `minio/minio:RELEASE.2025-04-22T22-12-26Z`: signed size/checksum/type, oversized/altered-content rejection, authorization expiry, metadata inspection, streaming reads, bounded sentinel reads, configured-origin CORS, untrusted-origin rejection, and idempotent delete. `S3ObjectStoragePropertiesTest` accepted loopback HTTP and rejected an external cleartext browser-upload endpoint.
- `ObjectUploadLifecycleIntegrationTest` passed against PostgreSQL: wrong-Tenant lookup, integrity mismatch/retry with preserved provider cause, adoption replay rejection, adopted retention, expired pre-adoption cleanup, per-row deletion failure isolation/recovery, and verification/cleanup fencing.
- `PostgresSourceLifecycleTest` passed: duplicate convergence, finalized-receipt replay, discarded-object reaping, adopted-object removal, and restrictive-FK cleanup.
- `SourceApiIntegrationTest`, `BearerAuthenticationIntegrationTest`, and `OpenApiContractTest` passed for OWNER authorization, initiate/finalize JSON commands, anchored SHA-256 validation, and the committed browser contract.

## Runtime surfaces

- `DefaultIngestionCoordinatorTest` proved cleanup lease renewal and cancellation; `WorkerFileProcessingIntegrationTest.redisStreamsIndexRemoveAndDeleteOneRealFile` passed with real PostgreSQL, Redis Streams, digest-pinned MinIO, the S3 adapter, Tika child extraction, indexing, item removal, source deletion, provider-object absence, and relational object/upload cleanup.
- The FILE Source Playwright scenario passed in Chromium. Network assertions observed one PUT to `https://objects.example.test`, exact checksum/content-type headers, no file bytes in any `/api/sources/**` body, a failed finalize response, selection of another source, and successful retry against the initiating source without a second PUT.
- `direct-upload.test.ts` covered browser SHA-256, required-header transport, progress, provider rejection, and cancellation.
- Production `api`, `worker`, and `web` images built successfully. A running production web image returned `200` with `connect-src 'self' http://localhost:19000` substituted into its CSP.

## Source catalog and setup correction

- The authoritative Onyx checkout under `.tmp/onyx/` confirmed two separate surfaces: provider metadata tiles at `/admin/add-connector`, followed by a provider-specific setup shell. MemoryOS adopts that information architecture without copying Onyx's deprecated numeric `FormContext` or FILE-specific skip logic.
- The persistent administration shell now contains `/admin/sources/new` as a nested catalog leaf and `/admin/sources/new/file` as a provider setup leaf. A typed `SourceSetupWizard` owns progress and navigation while FILE supplies its single real `configuration` step. Successful creation returns to `/admin?sourceId={createdSourceId}`.
- The complete Chromium Playwright suite passed: 15 tests, including provider catalog selection, disabled-until-valid FILE configuration, guarded creation, URL-selected detail, browser back/forward, direct PUT/finalize retry, item removal, Source deletion, and denied member deep links without protected Source requests.
- A headless Chromium visual smoke rendered and captured the empty configured-Source page, provider catalog, populated FILE wizard, and created Source detail. The observed final URL selected the exact created Source ID.
- `pnpm check` passed generated API stability, the pinned Playwright image check, warning-free lint, formatting, TypeScript, 44 unit tests, generated route-tree stability, production build, and emitted-font checks. `./gradlew.bat clean check --no-daemon` passed all 23 actionable repository tasks after Docker Desktop was started; the immediately prior attempt failed only because no Docker daemon was available.
- JetBrains MCP was not mounted for this correction, so no new IDE-clean claim is made. LSP reported no diagnostics for the changed product TypeScript and route files. Its standalone Playwright-file context lacked the repository's Node globals, while the authoritative project `tsc -b` and Playwright compilation both passed.
- CodeRabbit's single review pass found two valid edge cases. Blank/whitespace `sourceId` values are now trimmed to no selection, and the wizard takes a synchronous completion lock before awaiting provider creation. The FILE browser scenario fires two same-tick clicks against a delayed creation response and observes exactly one create request.

## Deployment

- `docker compose -f infrastructure/deployment/compose.base.yaml config -q` passed with required deployment values.
- `infrastructure/minio/bootstrap.sh` ran twice against the pinned MinIO/`mc` images and a durable volume before the review fix. Both runs completed successfully, kept the bucket private, reconciled users/policies/sentinel, used `/tmp` for `mc` state under a read-only root filesystem, and authenticated the distinct API and worker identities by reading the sentinel. The post-review bootstrap now terminates after 30 failed authentication attempts.
- `infrastructure/minio/provision-secrets.sh` created a complete mode-`0600` secret set and replayed idempotently.
- `sh -n` passed for the bounded MinIO bootstrap.

## Cutover boundary

V9 is intentionally destructive for the early-project environment: it removes `content_bytes` and requires a reset/empty application database. Rollback is whole-release restore of the pre-V9 PostgreSQL backup and pre-cutover images. No BYTEA fallback, dual-write, reverse migration, or mixed-version rolling path exists.
