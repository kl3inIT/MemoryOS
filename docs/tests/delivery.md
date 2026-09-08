# Delivery verification

| Contract | Verification | Evidence boundary |
| --- | --- | --- |
| Required dependencies cannot silently skip integration | Mandatory Testcontainers classes; `clean check`; bounded no-Docker JUnit probe | Docker absence fails a required container; the three opt-in live Docling checks remain separate |
| Runtime defaults survive context consolidation | `BearerAuthenticationIntegrationTest` | HTTP health, virtual-thread executor, disabled normal springdoc handler and bearer security in the same context |
| Frontend failures remain visible | Vitest worker cap, zero-retry fixture Playwright suite | Synthetic browser/API behavior, not a deployed provider |
| A failed/skipped job cannot authorize release | `CI Gate` checks the exact dependency set and every result with jq | GitHub workflow execution; no path filtering skips required jobs |
| The deployed artifact is the main artifact | CI image archive publication, source labels, digest references, release run/attempt and configuration checksums | Main publication required; a PR run builds but cannot publish or deploy |
| Workflow and script syntax/configuration remain valid | actionlint and ShellCheck in CI; IDE checks on workflow YAML | Static checks do not prove a server rollout |
| API migration/readiness precedes new worker | Compose script sequential `--wait` rollout with explicit deadlines | Exercise failure and recovery on staging before enabling automatic deployment |
| Concurrent or interrupted mutation is contained | GitHub concurrency, server flock and persistent reservation | A pending transaction blocks a newer release until accepted/recovered |
| Cancellation attempts recovery without releasing an uncertain runtime | Rollback condition includes failure and cancellation only after rollout starts; the server lock and smoke-before-finalization remain mandatory | Runner termination can interrupt cleanup; operator recovery retains the reservation |
| Smoke credentials stay out of dependency installation | Step-scoped secrets; staging test collection succeeds without username/password | Configuration preflight still rejects missing credentials before server mutation |
| Real accepted Search flow | `web/tests/staging/search.spec.ts`, normal identity and FILE/Search UI | Dedicated account, real Keycloak/MinIO/worker/OpenSearch; prints only its own cleanup source ID |
| Rollback is explicit and compatible | Prior image IDs/configuration, schema comparison, database backup, repeated live smoke | Backup catalogue is not restore proof; changed schema needs operator recovery |

The first manual deployment, compatible rollback rehearsal and automatic-deploy activation require the environment and identity configuration in the [runbook](../runbooks/ci-cd.md). Do not report these as passed from local tests or workflow validation. In-flight commands/results belong in the [MEM-70 verification record](../increments/active/mem-70-testing-cicd/verification.md); post-merge runtime evidence belongs in Linear.
