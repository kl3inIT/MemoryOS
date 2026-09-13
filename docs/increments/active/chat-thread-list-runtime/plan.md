# Plan

## A. Archive backend (own commit)

- [x] V51 `archived_at` and partial index.
- [x] Core: `ChatSession.archived`, repository list filter, archive/unarchive with owner lock, search and project lists exclude archived.
- [x] API: `status` list parameter (`REGULAR` default, `ARCHIVED`, `ALL`), archive endpoints, `archived` response field; regenerate OpenAPI and web client.
- [x] Tests: `ChatPersistenceIntegrationTest` (list filter, idempotency, unchanged activity time, search exclusion, rename while archived, owner denial) and `ChatSessionApiIntegrationTest` (CSRF, owner 404, list filters, invalid status 400, archived history readable), OpenAPI contract.
- [x] Update `docs/specs/chat.md` and `docs/tests/chat.md`.

## B. Web runtime migration (own commit)

- [x] Thread-list adapter with history adapter; unit tests with a fake API.
- [x] Per-thread controller registry; the transport resolves thread initialization when it creates the session.
- [x] Mount the list runtime under the authenticated route; `ChatPage` renders the main thread and syncs the URL.
- [x] Sidebar on thread-list state, archive section, row actions through the thread-list item; page title generation removed.
- [x] Keep project lists, drag-and-drop, history search and shared page working.
- [x] Typecheck, lint, i18n, unit tests; Playwright chat suites with one worker plus `chat-archive.spec.ts`.
- [x] `verification.md`; consolidate into specs/tests; register in `AGENTS.md`.
- [ ] Whole-repository `clean check` before the pull request.
