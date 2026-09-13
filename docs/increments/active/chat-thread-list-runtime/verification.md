# Verification — 2026-09-13

Local evidence only; no deployment, push or live provider run.

## Archive backend (commit `91f3329`)

- `ChatPersistenceIntegrationTest` 21/21 on real PostgreSQL/Flyway (V51). Covers list filters, idempotent archive with unchanged activity time, search exclusion, rename while archived and owner denial.
- `ChatSessionApiIntegrationTest` 3 focused methods. Covers CSRF, owner 404, `REGULAR`/`ARCHIVED`/`ALL`, invalid status 400 and archived history readable.
- `OpenApiContractTest` 1/1 after regeneration; `ModulithArchitectureTest` and `CoreDependencyRulesTest` pass; Worker compiles.

## Web runtime migration

- Lint, typecheck (`tsc -b`), format check and i18n audit (zero findings) pass.
- Unit tests: 40 files, 216 tests pass. The new `chat-thread-list-adapter.test.ts` covers:
  - `ALL` list with an offset cursor;
  - row reconstruction;
  - initialization from the transport session, with retry after a failed first send;
  - title generation only after the first completed answer;
  - linear history load that defers a RUNNING reply and makes no request for a new thread.
- Playwright, one worker, synthetic fixture server: `chat`, `chat-workspace`, `chat-history-search`, `chat-ui-polish` and `chat-sources-toolbar` passed 51/52. The only failure was a `page.goto` load timeout in the workspace delete scenario after the shared-page checks; it passed on an immediate rerun and is recorded as a timing flake, not fixed.
- New `chat-archive.spec.ts` passes: sidebar archive, archived section, opening an archived conversation unarchives it and returns it to the regular list.

## Defects found and fixed during verification

- The provider sits above the chat routes and did not receive `sessionId` from route params, so a promoted conversation was switched back to a new thread. It now derives the session from the pathname.
- Visited thread bodies stay mounted in the list host, which kept a switched-away conversation's SSE reader open (`chat.spec.ts` mobile drawer asserts zero readers). Hidden threads now close their reader and stop the local chat; when shown again they reconcile from saved history and resume a RUNNING reply.

## Existing e2e assertions

No existing assertion was changed. The server-ID promotion test still asserts no session/history read and a mounted composer.

## Boundaries

- No background thread runs (library limitation, see design). Sidebar running state is live only for the visible thread.
- List load errors are logged by assistant-ui and render as an empty list; no dedicated retry control.
- Whole-repository `clean check` not yet repeated after this increment.
