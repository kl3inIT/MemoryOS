# MEM-70 verification

Baseline: `d45afa28eba4a3bcde99d1195d2a6bb5ee677129`; implementation branch `mem-70-testing-cicd`. All six unrelated roadmap/Google Drive/chunking files match their recorded pre-work SHA-256 hashes. Measurements below are from the Windows development host on 2026-09-08, except the explicitly isolated Linux probes.

| Check | Observed result and boundary |
| --- | --- |
| `gradlew.bat clean check --no-daemon` | Passed in 5m 42s after the final Java/property changes. 217 invocations: 214 passed, three opt-in Docling tests skipped, no failures. Mandatory PostgreSQL/Redis/MinIO/OpenSearch checks ran with Docker |
| Focused bearer/default-runtime and identity-value tests | Passed after moving all three smoke assertions into the existing bearer context and deleting only the generated accessor assertion |
| Mandatory Docker failure | Ran the compiled `SchedulerSchemaMigrationTest` through JUnit in the pinned Java 25 Linux container, with no network or Docker socket. Result: one failed container, zero skipped tests, explicit missing-Docker failure |
| `pnpm --dir web check` with `CI=true` | Passed generated API/route stability, lint, formatting, typecheck, 52 unit tests, production build and font-asset assertions; JUnit output uses `web/reports/unit.xml` |
| `CI=true pnpm --dir web test:e2e` | 22 Chromium fixture tests passed in 1.9 minutes with one worker and zero retries. Does not establish real provider acceptance |
| JetBrains per-file inspections and project build | Every changed Java/Kotlin DSL/workflow file inspected with warnings enabled; IDE build succeeded. Corrected the obsolete worker OTLP property and scoped ephemeral-database/JUnit lifecycle inspection suppressions. Two weak suggestions remain: the intentional unknown negative-test HTTP header and optional extraction of fixture-construction code into another method |
| actionlint 1.7.12 / ShellCheck 0.11.0 | Workflow validation including inline shell scripts and the server script passed in Linux containers. These are static checks, not a live rollout |
| Bounded server-script process probes | Passed inside a disposable Linux container without a Docker socket, using a command-boundary Docker stub: API failure prevents new worker/web startup; unchanged schema restores old image IDs/configuration; changed schema prevents old-image rollout and retains the reservation; pending state and real flock exclude another mutation; mutable references are rejected before Docker mutation |
| Actual aggregate-gate jq expression | Success accepted; failed, skipped, canceled and missing required results rejected in the Linux probe |
| Existing infrastructure checks | Grafana credential-boundary test passed; two OpenSearch Python checks passed and two Linux certificate/permission checks were explicitly skipped on Windows. The Linux CI job remains responsible for those platform-specific checks |
| Staging smoke collection/typecheck | The one real smoke test collects with complete synthetic configuration and typechecks against generated API types; no live account/provider result is claimed |
| Gitleaks 8.30.1 history scan | 175 baseline commits scanned. Six reviewed false positives are excluded by exact historical fingerprint only: browser-test prose, image tags and a deterministic unit-test cipher key. No whole documentation/test path is excluded |

The local fault probes are bounded verification tooling in the ignored work area, not another application/test framework or a claim that Docker stubs prove deployment. The durable gates remain Gradle, Vitest, Playwright, actionlint, ShellCheck and Gitleaks.

PR [#81](https://github.com/kl3inIT/MemoryOS/pull/81), initial head `7b53299d0fc60f607015ea2f37c0cc5ad4c02ebf`: [CI run 34205232613](https://github.com/kl3inIT/MemoryOS/actions/runs/34205232613) passed backend tests, both Linux infrastructure suites, frontend checks/browser tests, all three image builds and the secret scan. It failed inline-shell lint because Docker tag/build-argument variables were unquoted; the aggregate gate correctly failed and publication was skipped. Quoting is corrected, Linux actionlint/ShellCheck pass, and that cheap validation now runs before Gradle. The same fix also snapshots accepted private server configuration; a process probe verifies recovery still uses that snapshot after the desired environment file changes.

CI [34206545130](https://github.com/kl3inIT/MemoryOS/actions/runs/34206545130) passed every verification job and `CI Gate` for `bca0df0328841b6ec81e380720996c9e75f13c56`. Publication was correctly skipped for the PR.

The user's renewed review/merge directive followed completion of the original CodeRabbit request. Two actionable findings were captured: job-wide smoke credentials and missing cancellation recovery. The review fix scopes credentials to configuration validation and smoke/recovery steps, and the rollback condition now includes cancellation only after rollout starts. Test collection passes with username/password absent; frontend typecheck, focused lint/format checks, Linux actionlint/ShellCheck and IDE inspection/build pass. Nine cases exercise the actual rollback expression, including cancellation, success, skipped and never-started rollout. These expression checks do not prove live runner cancellation recovery; timeout/runner-loss recovery remains bounded by the persistent server reservation and the operator runbook. Final-head CI is required after this fix push.

## Source and performance limits

The exact video's English automatic transcript was fetched successfully: 1,382 segments ending at 52:30. [Research notes](research.md) map talk timestamps and slide pages to each applied principle. Automatic captions can misrecognize technical terms; the speaker's pinned slides corroborate those terms. CI/CD design is attributed separately.

The same 52-test frontend suite measured 25.34s with default workers and 19.69s with two workers in the initial comparison. A later configured run took 17.76s; these are local observations, not a controlled throughput benchmark. The historical worker-fork failure was not reproduced. Removing one full API context is established structurally; no context-startup speedup percentage is claimed.

## Required external and post-merge proof

Still required: final PR-head CI/image builds and recorded review resolutions; successful main publication; GitHub staging environment/identity configuration; first manual digest-based rollout; real authenticated Search smoke; compatible live rollback/recovery rehearsal; measured interruption/migration duration; then automatic deployment activation. The user explicitly authorized merge after review on 2026-09-08. A GitHub read on that date confirmed no environments, Actions secrets or variables are configured. `STAGING_AUTO_DEPLOY` stays unset until live proof passes. These are not marked complete from the local/static checks above.

The repository adds the real Playwright staging flow and its configuration preflight, but no staging smoke credentials or bypass account. See the [CI/CD runbook](../../../runbooks/ci-cd.md) for exact configuration and recovery steps. Post-merge evidence belongs in Linear; keep this increment active until merge.
