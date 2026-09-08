# MEM-70 plan

> Completed — reconciled 2026-09-08. Delivered through [PR #81](https://github.com/kl3inIT/MemoryOS/pull/81); closed by the task owner with health-only staging acceptance on 2026-09-08. Business smoke, live rollback rehearsal and automatic deployment activation were not required for this closure and were not performed. GitHub staging deployment credentials remain unconfigured and automatic deployment remains disabled; historical unchecked steps below are not completed evidence. Post-merge evidence: [MEM-70](https://linear.app/memory-os/issue/MEM-70).

- [x] Read the current issue, repository guidance and existing test/workflow definitions; capture baseline SHA and preserve unrelated work.
- [x] Move MEM-70 to In Progress and create the scoped implementation branch.
- [x] Write the proposed staging flow, implementation order and external configuration prerequisites in `design.md`.

## Phase 1 — Test strategy and CI reliability

- [x] Map selected Spring I/O 2026 practices using the exact video's English automatic captions, timestamps and the speaker's slides; distinguish those sources from MemoryOS-specific delivery policy.
- [x] Complete the backend/frontend/browser/runtime inventory, including infrastructure prerequisites, context/container lifecycles, skip ownership and prioritized gaps.
- [x] Measure the current frontend unit suite and a bounded-worker candidate: 52 tests passed in 25.34 s and 19.69 s respectively on the current Windows host. Historical fork errors were not reproduced.
- [x] Make required infrastructure unavailable -> failed integration gate observable; retain independently runnable unit tests and explicit optional-provider prerequisites.
- [x] Bound frontend worker usage and make retry/flaky outcomes visible without weakening assertions.
- [x] Pin actions/toolchains, retain dependency locks and add useful backend/frontend failure reports with explicit retention and redaction.
- [x] Add a stable aggregate gate and PR cancellation policy; prove failed/skipped required jobs cannot authorize release.

Exit: successful full local checks and CI on the exact candidate SHA, plus negative-case evidence for mandatory infrastructure and aggregate gate failures. Record timings and remaining gaps without claiming a historical flaky failure is fixed.

## Phase 2 — Release identity and publication

- [x] Implement preservation and publication of the API/worker/web image outputs only after all required main gates succeed; live main publication remains a post-merge gate.
- [x] Record source SHA, CI run/attempt, image digests and configuration checksums in the verified release bundle; validate identity before deployment.
- [x] Restrict publication permissions to trusted main execution and keep PR runs free of deployment authority.
- [ ] Prove a wrong digest, wrong revision or failed source run is rejected before any environment mutation.

Exit: a verified release can be fetched by digest with matching provenance, and the staging path performs no image rebuild.

## Phase 3 — Staging deployment, acceptance and recovery

- [ ] Prepare the exact GitHub staging environment, dedicated SSH identity, host trust and smoke-account configuration; record external setup separately from checked-in implementation.
- [x] Implement one deployment path with GitHub concurrency plus a shared server lock/reservation; preserve running deploys and reject unintended stale promotion.
- [x] Add preflight, disk/backup verification, configuration capture and compatibility-aware writer quiescence.
- [x] Enforce normal API/Flyway migration success and readiness before worker/web rollout completion, with deadlines and diagnostics.
- [ ] Exercise a first manual staging release through this path and verify the deployed SHA/digests.
- [ ] Run isolated authenticated upload -> ingestion READY -> Search -> reader smoke, including denied access and cleanup.
- [ ] Exercise compatible image/config rollback and smoke; document the separate recovery path for incompatible schema changes.
- [ ] Enable automatic staging deployment for subsequent successful main releases after the manual path is proven.

Exit: deployment failure is visible, overlapping deployment cannot mutate the environment concurrently, successful smoke refers to the actual release, and rollback evidence names the artifact and schema/configuration compatibility tested.

## Final verification and delivery

- [x] Run changed-file IDE checks, focused regressions, frontend checks and terminating `clean check`; collect measured results.
- [x] Update canonical testing guidance, a verification matrix and the CI/CD runbook in the same substantive change.
- [x] Publish PR #81 and attach it to MEM-70; document exact external staging prerequisites.
- [ ] Converge latest-head CI and review. CI passed for `bca0df0`. The user's renewed review/merge request captured two completed CodeRabbit findings: scope smoke credentials to necessary steps and attempt rollback on cancellation. Both are addressed in the review fix; final-head CI remains required.
- [ ] Follow the repository PR lifecycle for merge authorization, exact merged-SHA validation and post-merge Linear evidence.

No production deployment or branch-protection change is authorized by this increment. No additional issue or unrelated implementation is started.
