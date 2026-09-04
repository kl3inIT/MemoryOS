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

- The authoritative Onyx checkout under `.tmp/onyx/` confirmed three distinct surfaces: `/admin/indexing/status` is a full-width configured-connector list, each row navigates to `/admin/connector/{id}`, and `/admin/add-connector` uses compact 160px icon-and-label `SourceTile` links before provider-specific setup. MemoryOS follows that structure without copying Onyx's deprecated numeric `FormContext` or FILE-specific skip logic.
- `/admin` now renders only the full-width configured-Source list. Rows navigate to `/admin/sources/{sourceId}`, which alone owns upload, indexing, cleanup, and destructive actions. Creation returns directly to that detail path; query-owned selection and the master-detail pane are absent.
- `/admin/sources/new` renders FILE exactly once under the nonempty `Popular` category as a compact icon-and-label tile with no description card or search. `/admin/sources/new/file` retains the typed provider-owned wizard but presents its single FILE configuration step through a lightweight progress rail, centered setup body, and navigation row.
- Headless Chromium asserted the exact catalog URL plus `Add a source` heading before capture, and the exact FILE setup URL plus `Name this file source` heading before capture. Separate captures verified the configured-Source list, compact catalog, lightweight wizard, and dedicated Source detail.
- The focused Chromium scenarios passed configured-list navigation, absence of detail controls on the list, compact catalog selection, disabled-until-valid FILE configuration, single-flight creation, dedicated-detail browser history, direct PUT/finalize retry, item removal, Source deletion, and denied member deep links without protected Source requests.
- The synchronous completion lock from CodeRabbit's prior review remains enforced and tested with two same-tick clicks against a delayed creation response.
- JetBrains MCP remains unavailable for this correction, so no new IDE-clean claim is made. LSP and the checked-in frontend gates provide the static-analysis fallback.
- On the corrected head, LSP reported no diagnostics for every changed Source product and route file. `pnpm check` passed generated-client stability, warning-free lint, formatting, TypeScript, 44 unit tests, route-tree stability, and the production build; the complete Chromium Playwright suite passed all 15 scenarios.
- The terminating `./gradlew.bat clean check --no-daemon` passed all 23 actionable repository tasks on the corrected head; `git diff --check` also passed.

## Deployment

- `docker compose -f infrastructure/deployment/compose.base.yaml config -q` passed with required deployment values.
- `infrastructure/minio/bootstrap.sh` ran twice against the pinned MinIO/`mc` images and a durable volume before the review fix. Both runs completed successfully, kept the bucket private, reconciled users/policies/sentinel, used `/tmp` for `mc` state under a read-only root filesystem, and authenticated the distinct API and worker identities by reading the sentinel. The post-review bootstrap now terminates after 30 failed authentication attempts.
- `infrastructure/minio/provision-secrets.sh` created a complete mode-`0600` secret set and replayed idempotently.
- `sh -n` passed for the bounded MinIO bootstrap.

## Cutover boundary

V9 is intentionally destructive for the early-project environment: it removes `content_bytes` and requires a reset/empty application database. Rollback is whole-release restore of the pre-V9 PostgreSQL backup and pre-cutover images. No BYTEA fallback, dual-write, reverse migration, or mixed-version rolling path exists.
