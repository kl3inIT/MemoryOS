# Current-document correction

## PR review follow-up

Align Docling request configuration with the pinned server ceilings (200 pages, 300 seconds); test defaults, exact limits, lower limits and invalid overrides. Retain the approved private single-host Docker HTTP service endpoint for MinIO; public browser uploads remain HTTPS. Do not expand this PR into internal PKI provisioning. Verify review findings against the object-storage contract before resolution.

User-approved direction: follow Onyx's current-document model, not immutable extraction history.
Reference: `.tmp/onyx` e3320d8fe, Document/FileRecord models and indexing staging/promotion.

Keep stable Document identity with current metadata, source checksum and artifact reference.
Remove profile/output pinning; parser configuration is diagnostic metadata only. Replace
references transactionally under the operation claim and reap only unreferenced artifacts.

V12 removes version history and database text after copying current metadata/reference.
The user explicitly approved dev/staging migration without strict data preservation: missing
legacy artifacts do not block migration and normal FILE reindex can regenerate them later.
No alternate runtime/backfill mode is added. Connector input snapshots remain unchanged.
Explicit FILE reindex still extracts again; Google timestamp dedup and search status are not
invented before those capabilities exist.

- [x] Implement current-document publication, dev/staging migration and removal of pinning.
- [x] Test replacement, changed retry output, rollback, cleanup and migration with/without artifacts.
- [x] Run checks and real FILE/Redis/MinIO smoke; reconcile canonical docs and runbook.

## Verification — 2026-09-06

The checkpoint below predates integration with main's MEM-57/MEM-64 changes. Integration retains their metrics/tracing and shipped V10 migration; the unshipped extraction migrations are now V11/V12. Combined verification is recorded below when complete.

- `DOCLING_TEST_ENDPOINT=http://127.0.0.1:15063` and `gradlew clean check --rerun-tasks --no-build-cache --no-daemon`: PASS, 157 tests, zero failures/errors/skips; all 23 tasks executed.
- Real PostgreSQL migration (then V10-to-V11, now renumbered V11-to-V12) covered current artifact references and legacy null artifacts; version/profile tables and database text are absent afterward.
- Five artifact lifecycle tests cover current-reference replacement, changed retry output, rollback, cleanup and uncertain writes.
- Real Docling CPU v1.32.0 parsed Vietnamese DOCX, scanned PDF and PPTX. FILE worker smoke exercised DOCX upload, Redis recovery/duplicate delivery, MinIO artifact bytes, Document publication and source cleanup.
- Corrected the worker smoke's timing-dependent exact dispatch-count assertion: recovery requires at least two dispatches, while the existing one-Document assertion still verifies idempotency. Rediscovery may relay more than twice while the test deliberately stops its consumer.
- `git diff --check`: PASS. Frontend unchanged. No commit, merge, live migration or staging deployment performed for this correction.

## Main integration verification — 2026-09-06

- Integrated main at `31caa72` (MEM-57/MEM-64). Retained outcome metrics, initial queue-wait timers, trace-origin links and telemetry tests alongside artifact publication.
- Preserved main's V10 migration unchanged. Renumbered the unshipped extraction migrations to V11/V12 and updated migration fixtures and documentation.
- With real Docling at `http://127.0.0.1:15063`, `gradlew clean check --rerun-tasks --no-build-cache --no-daemon`: PASS, 171 tests, zero failures/errors/skips, all 23 tasks executed. This includes real FILE/Redis/MinIO extraction plus OTLP metrics and span assertions.
- No live database migration or deployment performed. Unrelated Google Drive/chunking planning changes are excluded from this integration.
