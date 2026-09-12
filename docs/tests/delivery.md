# Delivery verification

> Delivery policy — 2026-09-12: CD ends at verified image identity, migration/startup and health/readiness. Business acceptance belongs to the user. Recovery is manual; deployment success does not prove login/Search acceptance or restore correctness.

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
| The landing release stays independent of the application release | `landing-image` artifact outside `candidate-*`; `Publish landing` after landing and secrets; the application `images.env` keeps three images | Deployment is a manual operator step; the application script never manages `memoryos-landing` |
| API migration/readiness precedes new worker | Compose script sequential `--wait` rollout with explicit deadlines | Exercise failure and recovery on staging before enabling automatic deployment |
| Concurrent or interrupted mutation is contained | GitHub concurrency, server flock and persistent reservation | A pending transaction blocks a newer release until accepted/recovered |
| Failure/cancellation does not silently roll back or remove a reservation | `test_deploy_workflow.py`: reporting-only failure step, no SSH mutation; server lock retained | Operator explicitly selects recovery; runner termination can interrupt credential cleanup |
| Deployment does not depend on a business account | `test_deploy_workflow.py`: no smoke variables/secrets, Node, pnpm or Playwright commands in CD | User performs business acceptance; optional local tooling remains available but is not a deployment gate |
| Recovery cannot silently discard a reservation | Optional exact `recovery_release`, main ancestry check, existing transaction `finish` image/health/ownership guard | Manual selection only; never deletes pending directly, restores a database or accepts a mixed runtime |
| Real accepted Search flow | User acceptance through normal identity and FILE/Search UI; optional `web/tests/staging/search.spec.ts` | Not run by CD; record separately from deployment success |
| Rollback is explicit and compatible | Prior image IDs/configuration, schema comparison before restoring images, database backup, health/revision verification | Backup catalogue is not restore proof; changed schema needs operator recovery |

Deployment and manual recovery require the SSH/server configuration in the [runbook](../runbooks/ci-cd.md), not a smoke identity. Static regression checks and workflow validation do not establish live deployment or rollback correctness. In-flight evidence belongs in the [active delivery simplification increment](../increments/active/staging-deploy-simplification/plan.md); post-merge runtime evidence belongs in Linear.
