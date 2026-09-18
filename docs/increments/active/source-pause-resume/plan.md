# Source Pause and Resume implementation plan

## Goal

Implement Source-level Pause/Resume that blocks new work, drains active work safely, preserves history/checkpoints, and makes the state clear in the Source UI.

## Tasks

- [ ] Add paused Source state in persistence and summaries  
      Verify: migration and repository tests show `PAUSED` persists, read pages remain authorized/readable, deleting remains stronger.

- [ ] Add pause/resume management service methods and API endpoints  
      Verify: API integration tests require Source manage authority, reject deleting Sources, and return updated Source summaries.

- [ ] Block paused Sources before Redis dispatch  
      Verify: dispatch repository tests show SOURCE_SYNC and INGESTION candidates for paused Sources are not claimed/published, while cleanup/deletion candidates still run.

- [ ] Cancel or drain queued and active SOURCE_SYNC work safely  
      Verify: a run paused during traversal stops without provider failure, retains frontier/page-token state, and resume enqueues a new run that continues or safely revisits without losing ACL revocations.

- [ ] Cancel queued item indexing and fence active publication  
      Verify: queued attempts become `CANCELLED`; active attempts cannot publish after pause; retry budgets and Source error banners are unchanged.

- [ ] Add UI actions and paused-state copy  
      Verify: actual Source page shows Pause, Pausing and Resume states; Sync/Reindex disabled while paused; read-only Files, ACL and History remain usable.

- [ ] Add observability and history wording  
      Verify: pause/resume audit events exist; run history labels cancellation by pause distinctly from failure.

- [ ] Update connector/ingestion specs and verification matrices  
      Verify: docs state exact resource boundary, Docling limitation, ACL freshness effect and non-goals.

- [ ] Final verification  
      Verify: `gradlew.bat clean check`, generated API + `pnpm check`, and real Orca smoke: start a long Source run, Pause, observe Pausing/Paused, confirm no new work starts, Resume, confirm work continues and files remain readable.

## Done when

- Source managers can pause and resume a Source from the UI.
- Paused Sources do not start new sync/index work.
- Active work stops at a safe boundary or truthfully remains `Pausing` until it finishes.
- Resume continues from retained state without forced full reindex.
- Pause is not reported as extraction/provider failure.
- Verification evidence covers backend, frontend and actual Orca runtime.
