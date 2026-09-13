# Verification — 2026-09-13

Local evidence only; no deployment, push or live provider run.

## Web runtime migration

- Lint, typecheck (`tsc -b`), format check and i18n audit (zero findings) pass.
- Unit tests: 40 files, 216 tests pass. `chat-thread-list-adapter.test.ts` covers:
  - offset list and row reconstruction;
  - initialization from the transport session, with retry after a failed first send;
  - title generation only after the first completed answer;
  - linear history load that defers a RUNNING reply and makes no request for a new thread.
- Playwright, one worker, synthetic fixture server: `chat`, `chat-workspace`, `chat-history-search`, `chat-ui-polish` and `chat-sources-toolbar` passed 51/52 before the archive withdrawal. The only failure was a `page.goto` load timeout in the workspace delete scenario; it passed on an immediate rerun and is recorded as a timing flake, not fixed.
- After withdrawing archive and moving the search entry, `chat-history-search`, `chat` and `chat-workspace` were rerun with one worker: 46/46 passed (7.2 min). `pnpm check:api` regenerates the client from the restored `openapi.yml` without changes.

## Defects found and fixed during verification

- The provider sits above the chat routes and did not receive `sessionId` from route params, so a promoted conversation was switched back to a new thread. It now derives the session from the pathname.
- Visited thread bodies stay mounted in the list host, which kept a switched-away conversation's SSE reader open (`chat.spec.ts` mobile drawer asserts zero readers). Hidden threads now close their reader and stop the local chat; when shown again they reconcile from saved history and resume a RUNNING reply.

## Archive withdrawal

The backend returned byte-for-byte to `693a1c6` (`git diff 693a1c6 -- core api openapi.yml web/src/lib/hey-api` is empty), so the earlier archive backend evidence no longer applies.

## Boundaries

- No background thread runs (library limitation, see design). Sidebar running state is live only for the visible thread.
- List load errors are logged by assistant-ui and render as an empty list; no dedicated retry control.
- Whole-repository `clean check` not yet repeated after this increment.
