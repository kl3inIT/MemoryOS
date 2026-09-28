# Plan

## Backend

- [x] A3 — Meeting minutes and audio jobs: fail a job whose final attempt's lease expired (mirror `UsageReportRepository.failAbandoned`), so the meeting leaves `TRANSCRIBING`/`RUNNING`.
- [x] A4 — `MeetingService.exportMinutes` runs in one read-only transaction; remove the misplaced annotation and the uncalled overload.
- [x] A7 — Source summary status/error reads the SharePoint provider error as well as Google Drive's.
- [x] A8 — A SharePoint storage failure retries the attempt without cancelling the run's checkpoints.
- [x] A14 — Invitation list paging errors use a typed `BusinessException` code instead of `ResponseStatusException` with the diagnostic message.
- [x] Small local fixes named in audit.md (logging on silent retries, shared constants, cheap efficiency fixes) where they touch no contract.

## Web

- [x] A1 — Meeting list invalidation matches the actor-scoped list key.
- [x] A2 — MCP pages surface server errors.
- [x] A5 — A failed meeting recording keeps its error visible.
- [x] A6 — Admin entry path covers chat-history-only access.
- [x] A9 — Sources filters come from the exported provider/status/access option lists.
- [x] A10 — History paging disables on `isPlaceholderData`, not background refetch.
- [x] A11 — Library favourite failures are caught and shown.
- [x] A12 — Meeting notes autosave cannot race itself into a false 409.
- [x] A13 — Group create/detail drafts are protected against in-app navigation with `useBlocker`.
- [x] Remove explanatory copy under controls flagged in audit.md.

## Verification — 2026-09-24

- `./gradlew clean check`: BUILD SUCCESSFUL (10 min) after all backend commits.
- `pnpm typecheck` (tsc -b), `pnpm lint`, `node scripts/i18n-audit.mjs --check`, `oxfmt --check src`, `pnpm test:unit` (140 files, 667 tests) and `pnpm build`: passed.
- `git diff origin/main -- openapi.yml web/src/lib/hey-api web/src/components/ui`: empty.
- New tests: `MeetingRepositoryTest` (abandoned leases), SharePoint FAILED status, SharePoint storage-failure retry keeps the run, `SessionSecurityIntegrationTest` (typed invitation query code), `meetings-api.test.ts`, `meeting-session.test.ts`, admin entry path, MCP errors, source history paging, library favourite failure, search results kept while paging, invitation error mapping.
- Not run: JetBrains Gate-1 inspection (MCP unreachable) and Playwright e2e. Notes autosave, group navigation blocking and correction polling have no new tests because the touched components have no test harness.
- `DefaultSharePointSourceService.java` was stored with CRLF on main; replacing its raw control characters let Git normalise it to LF, so its diff shows as a full rewrite (`git show --ignore-cr-at-eol afa577de` shows the 10 real lines; bytecode is identical).
