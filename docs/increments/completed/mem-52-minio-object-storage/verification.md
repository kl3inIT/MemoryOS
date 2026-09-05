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

- The authoritative Onyx checkout under `.tmp/onyx/` confirms two distinct management surfaces. `/admin/add-connector` uses a full-width stacked icon/title/action header, a full-width search field, and fixed icon-and-label `SourceTile` links grouped by category. `/admin/indexing/status` uses the same header rhythm, a search/collapse/filter toolbar, and provider `SummaryRow` groups whose six equal tracks align with the expanded connector columns.
- `/admin` now renders that structure as a semantic `<table>` with six equal tracks. Each provider summary exposes Total sources, Active sources, Public sources, and Total docs indexed; expanded rows expose Name, Last indexed, Status, Permissions / Access, Total docs, and an icon-only accessible Manage link. Search, status/access filters, and group/all collapse controls are functional; the table contains no embedded detail controls.
- `/admin/sources/new` mirrors the stacked header and full-width search composition, then renders FILE exactly once under `Popular` with Onyx's `w-40` geometry: `160px` width, `16px` padding, a `24px` icon, intrinsic content height, rounded corners, and a restrained shadow. `/admin/sources/new/file` retains the typed provider-owned wizard with a vertical 36px-row dot/connector rail, centered provider body, and three-column Previous/Create/Continue navigation row.
- The persistent administration layout retains pending finalization across Source routes. The Chromium scenario fails finalization after one provider PUT, navigates through the list into another Source, follows `Return to pending upload` to the initiating Source, retries successfully, and observes no second PUT.
- Headless Chromium rendered the screenshot-matched desktop list and catalog at `1568px` width. It measured the catalog tile at `160px × 84px` with `16px` padding, a `24px` icon, and `14px` label; it measured the provider title and metric values at `20px`. The same pass observed the exact list heading, semantic table, six-track summary/header alignment, status/access badges, and full-width search controls. At `390px`, the document body stayed at viewport width while the semantic table scrolled inside its own overflow region.
- The synchronous wizard completion lock remains tested with two same-tick clicks against a delayed creation response and exactly one create request.
- JetBrains MCP inspected the four changed Source feature files with warnings enabled and reported no problems. Its E2E-file inspection reports a false `void`/redundant-`await` pair on the pre-existing final Playwright `Locator.click()`; Playwright's typed promise compiles under repository `tsc -b` and executes successfully. LSP likewise reports no Source-feature diagnostics; its standalone E2E project lacks the repository's Node type environment, while repository TypeScript and Playwright compile the file.
- CodeRabbit's one review pass found one valid minor issue: an Enter keypress could read a stale deferred catalog search result. The fix removes deferred search state, derives navigation matches from the input's current normalized value, and adds a browser assertion that Enter on a no-match query stays on the catalog.
- On the screenshot-faithful head, `pnpm check` passed generated-client stability, warning-free lint, formatting, TypeScript, 44 unit tests, route-tree stability, and the production build; the complete Chromium Playwright suite passed all 15 scenarios. The terminating `./gradlew.bat clean check --no-daemon` passed all 23 actionable repository tasks.

## Deployment

- `docker compose -f infrastructure/deployment/compose.base.yaml config -q` passed with required deployment values.
- `infrastructure/minio/bootstrap.sh` ran twice against the pinned MinIO/`mc` images and a durable volume before the review fix. Both runs completed successfully, kept the bucket private, reconciled users/policies/sentinel, used `/tmp` for `mc` state under a read-only root filesystem, and authenticated the distinct API and worker identities by reading the sentinel. The post-review bootstrap now terminates after 30 failed authentication attempts.
- `infrastructure/minio/provision-secrets.sh` created a complete mode-`0600` secret set and replayed idempotently.
- `sh -n` passed for the bounded MinIO bootstrap.

## Cutover boundary

V9 is intentionally destructive for the early-project environment: it removes `content_bytes` and requires a reset/empty application database. Rollback is whole-release restore of the pre-V9 PostgreSQL backup and pre-cutover images. No BYTEA fallback, dual-write, reverse migration, or mixed-version rolling path exists.
