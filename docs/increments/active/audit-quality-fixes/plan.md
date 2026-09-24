# Plan

## Backend

- [ ] A3 — Meeting minutes and audio jobs: fail a job whose final attempt's lease expired (mirror `UsageReportRepository.failAbandoned`), so the meeting leaves `TRANSCRIBING`/`RUNNING`.
- [ ] A4 — `MeetingService.exportMinutes` runs in one read-only transaction; remove the misplaced annotation and the uncalled overload.
- [ ] A7 — Source summary status/error reads the SharePoint provider error as well as Google Drive's.
- [ ] A8 — A SharePoint storage failure retries the attempt without cancelling the run's checkpoints.
- [ ] A14 — Invitation list paging errors use a typed `BusinessException` code instead of `ResponseStatusException` with the diagnostic message.
- [ ] Small local fixes named in audit.md (logging on silent retries, shared constants, cheap efficiency fixes) where they touch no contract.

## Web

- [ ] A1 — Meeting list invalidation matches the actor-scoped list key.
- [ ] A2 — MCP pages surface server errors.
- [ ] A5 — A failed meeting recording keeps its error visible.
- [ ] A6 — Admin entry path covers chat-history-only access.
- [ ] A9 — Sources filters come from the exported provider/status/access option lists.
- [ ] A10 — History paging disables on `isPlaceholderData`, not background refetch.
- [ ] A11 — Library favourite failures are caught and shown.
- [ ] A12 — Meeting notes autosave cannot race itself into a false 409.
- [ ] A13 — Group create/detail drafts are protected against in-app navigation with `useBlocker`.
- [ ] Remove explanatory copy under controls flagged in audit.md.

## Verification

Pending.
