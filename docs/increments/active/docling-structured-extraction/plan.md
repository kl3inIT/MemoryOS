# Docling implementation plan

- [ ] Pin compatible Java SDK, server image, models and OCR assets; inspect current official docs and licenses during implementation.
- [x] Define canonical block/table/provenance schema for FILE and the later Google-native adapters; parser configuration is diagnostic metadata.
- [x] Replace version history with current Document metadata/reference through V11, without strict legacy-data preservation for dev/staging. Track correction verification in [current-document-plan.md](current-document-plan.md).
- [x] Implement bounded worker object write/adoption and orphan/last-reference cleanup with least-privilege deployment policy.
- [x] Implement Java Docling adapter, bounded private byte submission, failure taxonomy and output validation. The synchronous SDK FileSource request uses base64 bytes, not a fetch URL.
- [x] Integrate FILE PDF/DOCX/PPTX through the real ingestion consumer; retain TXT/Markdown and existing upload/retry UX.
- [x] Document the shared contract for Google Drive binary/Slides-export routes; implementation and acceptance belong to MEM-10.
- [ ] Test real service parsing against committed synthetic fixtures: Vietnamese prose, tables, headings, multi-column PDF, scan, malformed/encrypted and oversized input.
- [ ] Test worker/server restart, timeout with late result, token expiry, duplicate delivery, artifact write failure, source deletion and orphan cleanup.
- [ ] Benchmark fixed corpus with declared service/model versions, CPU/concurrency limits, peak RAM, latency and output correctness. Do not compare unpinned default backends.
- [x] Run `gradlew clean check`, changed frontend gates and real local FILE smoke. Record staging evidence after an authorized deployment, separately from local results. Drive smoke belongs to MEM-10.
- [x] Consolidate spec/test/architecture/runbook changes. Retain active increment until merge; reconcile status under the repository lifecycle rules afterward.

Not in scope: embedding, OpenSearch, GraphRAG, VLM descriptions, MinerU deployment, automatic GPU sharing or an independent extraction management UI.

## Historical verification checkpoint — 2026-09-06, before current-document correction

- Branch: `mem-61-docling-extraction`, based on `0279988`; initial implementation committed as `8beb1a4`. Results below precede the current-document correction and do not verify that correction. No merge or staging deployment recorded.
- `DOCLING_TEST_ENDPOINT=http://127.0.0.1:15063` with `gradlew clean check --no-daemon`: PASS, 156 tests, zero failures/skips. Core/API reused valid Gradle test cache; connector and worker service runs executed.
- Real Docling CPU v1.32.0, image/model digest recorded in design, read-only root, `/tmp` tmpfs, EasyOCR writable directory redirected to `/tmp/easyocr`, one conversion worker, 4-CPU quota.
- Real corpus: Vietnamese DOCX with table values; scanned PDF OCR/page provenance; PPTX slide text. All three passed. JUnit end-to-end durations were 49.997s, 19.761s and 2.898s respectively. These include client/startup/queue overhead and concurrent worker verification, not isolated parser latency.
- Container cgroup peak memory after that run: 1,616,003,072 bytes. Docker Desktop reported effective host capacity below the requested 8 GiB container limit. This is a local smoke measurement, not a production sizing result.
- Real FILE vertical slice: DOCX upload to MinIO, Redis dispatch/reclaim/duplicate delivery, worker/Docling extraction, artifact PUT/checksum, PostgreSQL publication, source removal and artifact cleanup: PASS.
- Five PostgreSQL artifact/profile lifecycle tests and two bounded HTTP transport tests: PASS. Existing stale-token lease-reclaim tests remain green.
- Frontend typecheck, lint, format check, build and 44 unit tests: PASS. Base Compose structural validation and `git diff --check`: PASS.
- Playwright FILE single-step setup: 4/4 PASS (success, create failure, upload failure, finalize failure), including the PPTX accept-list assertion and existing responsive-layout checks.

## Remaining release verification

- Expanded fixed corpus, including multi-column PDF and complex tables; final license inventory for the pinned model assets.
- Active-job service kill/restart and ambiguous network-write fault injection beyond the tested token/tombstone contracts.
- Isolated latency/concurrency/capacity benchmark on the target deployment hardware.
- Authorized staging deployment and exact-SHA runtime evidence. Keep MEM-61 open until remaining acceptance gates are explicitly satisfied or scoped by the project owner.
