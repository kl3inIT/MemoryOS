# MEM-75 selected upgrade plan

> Completed — reconciled 2026-09-08. The selected dependency batch was delivered through [PR #82](https://github.com/kl3inIT/MemoryOS/pull/82) and accepted on staging with health checks. This increment is complete; MEM-75 remains In Progress for the wider, unselected Dependabot backlog. Java 25 and Node 24 remain the selected runtimes. Post-merge evidence: [MEM-75](https://linear.app/memory-os/issue/MEM-75).

- [x] Inspect all 19 existing proposals, current CI/review evidence and user-selected scope.
- [x] Preserve unrelated dirty files and start from current main in the single-session checkout.
- [x] Record the Java 25 decision and close PR #53; explain the separate Node 26/Corepack failure.
- [x] Apply the eight selected versions and regenerate the pnpm lockfile.
- [x] Inspect changed IDE-supported files and compile; run the OpenAPI contract and resolve actual drift.
- [x] Run frontend check, generated route/client stability and the complete browser suite without retries.
- [x] Build and exercise the new Nginx image with a bounded isolated upstream.
- [x] Run terminating `clean check` and workflow lint; record evidence and consolidate lasting verification guidance.
- [x] Inspect upstream changelogs at the selected versions and distinguish current app relevance from unmeasured improvements.
- [ ] Publish the replacement PR, pass latest-head CI and triage one CodeRabbit review pass.
- [ ] Complete authorized merge and exact merge-SHA CI/publication proof, then close the eight superseded proposals.
- [ ] Record delivered and remaining scope in MEM-75; preserve the other pending proposals and unrelated checkout files.
