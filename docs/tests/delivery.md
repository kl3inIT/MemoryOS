# Delivery verification

> Delivery status — 2026-09-08: [MEM-70](https://linear.app/memory-os/issue/MEM-70) is closed with task-owner-approved health-only staging acceptance. The GitHub deployment path below is implemented but its dedicated credentials and automatic activation are not configured; business smoke and live rollback rehearsal were not performed. This closure does not assert those gates passed.

| Contract | Verification | Evidence boundary |
| --- | --- | --- |
| Required dependencies cannot silently skip integration | Mandatory Testcontainers classes; `clean check`; bounded no-Docker JUnit probe | Docker absence fails a required container; the three opt-in live Docling checks remain separate |
| Runtime defaults survive context consolidation | `BearerAuthenticationIntegrationTest` | HTTP health, virtual-thread executor, disabled normal springdoc handler and bearer security in the same context |
| Frontend failures remain visible | Vitest worker cap, zero-retry fixture Playwright suite | Synthetic browser/API behavior, not a deployed provider |
| A failed/skipped job cannot authorize release | `CI Gate` checks the exact dependency set and every result with jq | GitHub workflow execution; no path filtering skips required jobs |
| The deployed artifact is the main artifact | CI image archive publication, source labels, digest references, release run/attempt and configuration checksums | Main publication required; a PR run builds but cannot publish or deploy |
| Workflow and script syntax/configuration remain valid | actionlint and ShellCheck in CI; IDE checks on workflow YAML | Static checks do not prove a server rollout |
| Dependency upgrades preserve public API and frontend navigation | OpenApiContractTest, generated client/route stability, frontend check and zero-retry browser suite | Inspect generated differences before accepting an updated contract; fixture browser tests do not prove staging authentication |
| Web base-image upgrades preserve the serving boundary | Build the pinned image; inspect nginx version and rendered config; exercise SPA/asset routes, forwarding, CSP and invitation cache/log rules as its configured non-root user | A bounded synthetic upstream verifies proxy behavior; real provider and staging acceptance remain separate |
| The public landing image keeps its serving boundary | `landing/scripts/smoke-image.sh` in the `landing` CI job: read-only, capability-free UID 101 container; `/`, `/healthz`, public files, 404 for unknown paths, CSP and security headers, immutable asset caching | Local container only; DNS, TLS, HSTS and the `www` redirect are checked on the deployed host per the [landing runbook](../runbooks/landing.md) |
| The landing release stays independent of the application release | `landing-image` artifact outside `candidate-*`; `Publish landing` after `CI Gate`; the application `images.env` keeps three images | Deployment is a manual operator step; the application script never manages `memoryos-landing` |
| API migration/readiness precedes new worker | Compose script sequential `--wait` rollout with explicit deadlines | Exercise failure and recovery on staging before enabling automatic deployment |
| Concurrent or interrupted mutation is contained | GitHub concurrency, server flock and persistent reservation | A pending transaction blocks a newer release until accepted/recovered |
| Cancellation attempts recovery without releasing an uncertain runtime | Rollback condition includes failure and cancellation only after rollout starts; the server lock and smoke-before-finalization remain mandatory | Runner termination can interrupt cleanup; operator recovery retains the reservation |
| Smoke credentials stay out of dependency installation | Step-scoped secrets; staging test collection succeeds without username/password | Configuration preflight still rejects missing credentials before server mutation |
| Real accepted Search flow | `web/tests/staging/search.spec.ts`, normal identity and FILE/Search UI | Dedicated account, real Keycloak/MinIO/worker/OpenSearch; prints only its own cleanup source ID |
| Rollback is explicit and compatible | Prior image IDs/configuration, schema comparison, database backup, repeated live smoke | Backup catalogue is not restore proof; changed schema needs operator recovery |

The first manual deployment, compatible rollback rehearsal and automatic-deploy activation require the environment and identity configuration in the [runbook](../runbooks/ci-cd.md). Do not report these as passed from local tests or workflow validation. In-flight commands/results belong in the [MEM-70 verification record](../increments/completed/mem-70-testing-cicd/verification.md); post-merge runtime evidence belongs in Linear.
