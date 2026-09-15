# CI speedup

Owner request, 2026-09-14: CI/CD takes too long; apply the measured optimizations the owner selected (backend test parallelism, change-based job selection, Docker build cache) and compare with the owner's other repositories.

## Baseline (CI run 34851440247, PR #145, all jobs green)

| Job | Wall time | Where the time goes |
| --- | --- | --- |
| `check` | 11 m 24 s | `./gradlew clean check` 650 s; `:core:test` 8.5 min in one JVM (591 s of suite time, 525 tests). `OpenSearchRetrievalIntegrationTest` 161 s. `TestDatabase.freshPostgres()` (47 call sites, 26 in `@BeforeEach`) drops the schema and replays all 53 Flyway migrations per fixture |
| `frontend (1)` / `(2)` | 8 m 37 s / 6 m 48 s | Playwright 412 s / 363 s with one worker per shard; container start ~30 s; shard 1 adds `pnpm check` (49 s) |
| `backend-images` | 2 m 12 s | Gradle `bootJar` inside Docker with a cache mount that is empty on every hosted runner |
| `frontend-image` | 50 s | Vite build; `pnpm install` layer not reused between runs |
| Deploy staging | ~3 min | Unchanged |

Every PR ran every job, so PR wall time was the `check` job even for web-only changes.

## References

- OrgMemory `.github/workflows/ci.yml`: `changes` job (dorny/paths-filter) selects backend/web/cli/docs jobs; `build-images.yml` builds with `docker/build-push-action` and `cache-from/to: type=gha`.
- northstar `.github/workflows/ci.yml`: `changes` job with a `git diff` + `case` mapping, unknown paths run every area; gate accepts `skipped` only for unselected areas. `build-and-push.yml` uses a BuildKit registry cache and builds images only after main CI.

## Design

1. **Backend tests.** `core` tests run in two JVMs (`maxParallelForks = 2`); each JVM owns its PostgreSQL container. `freshPostgres()` migrates a `memoryos_template` database once per JVM and returns a `CREATE DATABASE … TEMPLATE` clone; clones whose pools are closed are dropped on later calls. `freshPostgres(version)` for a specific migration version keeps the old reset-and-migrate path. Fixture isolation is stronger than before: each fixture gets a new database instead of a reset `public` schema.
2. **Change-based selection (pull requests only).** A `changes` job diffs the PR merge commit against its first parent and maps paths to areas: backend (`check`, `backend-images`), web (`frontend`, `frontend-image`) and landing. `openapi.yml` selects backend and web; docs/markdown select nothing; unknown paths or an empty diff select every area. Main pushes always run and publish every area. `CI Gate` requires `changes` and `secrets` to succeed, every job of a selected area to succeed, and every job of an unselected area to be skipped. The landing smoke script's ShellCheck moves to the landing job; actionlint stays in `check`, which workflow changes always select.
3. **Docker build cache.** Image jobs use `docker/setup-buildx-action` and `docker/build-push-action` with `type=gha` caches (`backend-api`, `backend-worker`, `web`), `load: true`, and provenance/SBOM disabled so the preserved archive and its labels match the previous `docker build` output. The backend Dockerfile resolves production classpaths in a layer (`resolveDependencies`, `GRADLE_USER_HOME` inside the build stage) before copying sources, replacing the runner-local cache mount.

4. **Browser test sharding (owner-approved "follow best practice", 2026-09-14).** Playwright's [CI](https://playwright.dev/docs/ci) and [sharding](https://playwright.dev/docs/test-sharding) guidance: `workers: 1` per CI runner, scale horizontally with shards, `fullyParallel` for per-test splitting, blob reports merged afterwards; Playwright's own CI and Grafana shard the same way. The suite (~13 min of browser time) moves from 2 to 4 shards × 1 worker. The static/unit/build gate moves out of shard 1 into `frontend-check` on a plain runner (it needs no browser; the Playwright image check only reads the workflow text), so shards are balanced. Shards upload blob reports; the non-gating `frontend-report` job merges them into one HTML report.

## Not changed

- One worker per shard stays: the fixture server shares in-memory sessions/projects, and Playwright advises against parallel workers on CI.
- `clean check` stays the backend gate; publication, release artifacts and staging deploy are unchanged.
- No remote Gradle build cache or Develocity service.

## Risks

- A path mapping gap would skip a needed job on a PR; unknown paths therefore select every area and main always runs everything before publishing.
- Parallel core forks raise peak memory on the 16 GB runner (two 1 GiB test heaps, two PostgreSQL containers, OpenSearch); the measured run decides whether to keep two forks.
- The gha cache shares the repository's 10 GiB Actions cache budget with Gradle caches; old entries are evicted by GitHub.
