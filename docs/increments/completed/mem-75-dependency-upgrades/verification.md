# MEM-75 verification

Local checks on 2026-09-08 apply to the selected dependency batch from main `172b2ac5ed57c32ce791fdcf7df19179d02d86df`. PR, review and post-merge results are recorded in MEM-75 as they become available.

| Check | Result |
| --- | --- |
| Pinned pnpm 11.22.0 install | Passed; lockfile package changes are the selected five frontend dependencies and their required core/peer snapshots. Other direct versions remain unchanged |
| IDE inspections with warnings enabled | CI YAML, version catalogue and pnpm YAML have no reported problems on completed inspections. Initial catalogue timeouts resolved after IDE build; initial external action-input warnings cleared after upstream metadata became available |
| IDE build | Passed, no reported problems |
| `gradlew.bat :api:test --tests '*OpenApiContractTest*' --no-daemon` | Passed in 1m25s; no OpenAPI contract changes |
| `pnpm --dir web check` | Passed: client generation stability, CI image consistency, lint, formatting, types, 52 unit tests and generated-route stability/build |
| `pnpm --dir web test:e2e` with CI=true | All 22 Chromium fixture browser cases passed in 1.6m, retries disabled |
| Production web Docker build | Passed with the selected pinned Nginx digest and unchanged Node 24 build image |
| Real Nginx image probe | 17 assertions passed: Nginx 1.31.4, uid 101, rendered config validation, index/deep SPA route, CSP/storage origin, security headers, immutable assets, missing asset 404, API/health/OAuth/login/logout forwarding, invitation no-store/no-referrer and redacted logs. Upstream is an isolated synthetic HTTP server, not real OAuth or staging |
| `gradlew.bat clean check --no-daemon` | Passed in 2m14s: 217 reported invocations, 214 passed and the existing three optional live Docling cases skipped. No failures/errors; unchanged core, connector and worker tests restored from Gradle cache; API tests executed |
| Linux actionlint 1.7.12 | Passed against the checked-in workflows |

The Nginx probe uses temporary local containers and an isolated network, cleaned up after completion. No staging configuration or data was changed. No version-pin assertion tests or new application runtime modes were introduced. Changelog improvements are upstream claims; this batch verifies compatibility and does not benchmark performance.

Java 25 remains selected and PR #53 was closed by user decision. The Node 26 proposal #9 still fails its old image build at `corepack enable`: Node 25 and later no longer bundle Corepack. That separate migration and the other unselected proposals remain pending under MEM-75.
