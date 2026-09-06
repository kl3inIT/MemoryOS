# Current-document correction

User-approved direction: follow Onyx's current-document model, not immutable extraction history.
Reference: `.tmp/onyx` e3320d8fe, Document/FileRecord models and indexing staging/promotion.

Keep stable Document identity with current metadata, source checksum and artifact reference.
Remove profile/output pinning; parser configuration is diagnostic metadata only. Replace
references transactionally under the operation claim and reap only unreferenced artifacts.

V11 removes version history and database text after copying current metadata/reference.
The user explicitly approved dev/staging migration without strict data preservation: missing
legacy artifacts do not block migration and normal FILE reindex can regenerate them later.
No alternate runtime/backfill mode is added. Connector input snapshots remain unchanged.
Explicit FILE reindex still extracts again; Google timestamp dedup and search status are not
invented before those capabilities exist.

- [x] Implement current-document publication, dev/staging migration and removal of pinning.
- [x] Test replacement, changed retry output, rollback, cleanup and migration with/without artifacts.
- [x] Run checks and real FILE/Redis/MinIO smoke; reconcile canonical docs and runbook.

## Verification — 2026-09-06

- `DOCLING_TEST_ENDPOINT=http://127.0.0.1:15063` and `gradlew clean check --rerun-tasks --no-build-cache --no-daemon`: PASS, 157 tests, zero failures/errors/skips; all 23 tasks executed.
- Real PostgreSQL V10-to-V11 migration covered current artifact references and legacy null artifacts; version/profile tables and database text are absent afterward.
- Five artifact lifecycle tests cover current-reference replacement, changed retry output, rollback, cleanup and uncertain writes.
- Real Docling CPU v1.32.0 parsed Vietnamese DOCX, scanned PDF and PPTX. FILE worker smoke exercised DOCX upload, Redis recovery/duplicate delivery, MinIO artifact bytes, Document publication and source cleanup.
- Corrected the worker smoke's timing-dependent exact dispatch-count assertion: recovery requires at least two dispatches, while the existing one-Document assertion still verifies idempotency. Rediscovery may relay more than twice while the test deliberately stops its consumer.
- `git diff --check`: PASS. Frontend unchanged. No commit, merge, live migration or staging deployment performed for this correction.
