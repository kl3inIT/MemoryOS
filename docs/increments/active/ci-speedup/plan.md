# CI speedup plan

## 1. Backend tests

- [x] `TestDatabase.freshPostgres()` clones a once-per-JVM migrated template; version-targeted migration tests keep reset-and-migrate.
- [x] `core` test task `maxParallelForks = 2`.
- [ ] Measure `:core:test` and `check` wall time on CI against the 8.5 min / 11 m 24 s baseline; drop to one fork if memory pressure or flakiness appears.

## 2. Change-based job selection

- [x] `changes` job mapping PR paths to backend, web and landing areas; unknown paths and empty diffs select every area; main pushes select all.
- [x] Area jobs depend on `changes`; `CI Gate` accepts `skipped` only for unselected areas and requires `changes`/`secrets` success.
- [x] Landing smoke-script ShellCheck moved to the landing job.
- [ ] Observe a web-only, a backend-only and a mixed PR selecting the expected jobs.

## 3. Docker build cache

- [x] Backend Dockerfile dependency layer via `resolveDependencies` and `GRADLE_USER_HOME` inside the build stage.
- [x] `docker/build-push-action` with `type=gha` caches for API, worker and web; provenance/SBOM disabled to keep archives unchanged.
- [ ] Measure image job times on a second run with a warm cache; confirm the main publication still verifies revision/source labels.

## 3b. Browser test sharding

- [x] `frontend-check` job for `pnpm check`; `frontend` 4 shards × 1 worker with blob reports; non-gating `frontend-report` merge; gate includes `frontend-check`.
- [ ] Measure shard times against the 409 s / 363 s two-shard baseline.

## Measured (PR #149)

| Job | Baseline (run 34851440247) | e06bf23 attempt 1, cold image cache | e06bf23 attempt 2, same source, warm cache | cd9cf5e, 4 shards (run 34856580096) |
| --- | --- | --- | --- | --- |
| `check` | 684 s | 354 s | 306 s | 350 s |
| `frontend-check` | (in shard 1: 46–49 s) | — | — | 53 s |
| `frontend` shards | 517 s / 408 s | 514 s / 355 s | — | 278 / 301 / 227 / 234 s |
| `frontend-report` | — | — | — | 28 s |
| `backend-images` | 132 s | 368 s | 115 s | 175 s |
| `frontend-image` | 50 s | 119 s | 32 s | 75 s (web sources changed) |
| Run wall time | 11 m 31 s | 8 m 51 s | — | 6 m 37 s |

Image caching is not yet a clear gain: fully cached rebuilds are faster than baseline, but runs where the image inputs changed (or the cache missed) were slower because of BuildKit export/load and cache upload. Decide on the next backend and web PRs whether to keep it.

## 4. Documentation

- [x] Runbook, delivery verification matrix and AGENTS active increments.
- [ ] Record measured before/after times here before merge.

## Follow-ups (not in this increment)

- `setup-gradle` `cache-encryption-key` for configuration-cache reuse, Develocity OSS/remote cache.
