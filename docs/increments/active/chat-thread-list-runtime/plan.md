# Plan

## A. Archive backend (own commit)

- [x] V51 `archived_at` and partial index.
- [x] Core: `ChatSession.archived`, repository list filter, archive/unarchive with owner lock, search and project lists exclude archived.
- [x] API: `status` list parameter (`REGULAR` default, `ARCHIVED`, `ALL`), archive endpoints, `archived` response field; regenerate OpenAPI and web client.
- [x] Tests: `ChatPersistenceIntegrationTest` (list filter, idempotency, unchanged activity time, search exclusion, rename while archived, owner denial) and `ChatSessionApiIntegrationTest` (CSRF, owner 404, list filters, invalid status 400, archived history readable), OpenAPI contract.
- [x] Update `docs/specs/chat.md` and `docs/tests/chat.md`.

## B. Web runtime migration (own commit)

- [ ] Thread-list adapter with history and attachments adapters; unit tests with a fake API.
- [ ] Per-thread controller registry; transport initializes through the thread-list item.
- [ ] Mount the list runtime in the authenticated boundary; `ChatPage` renders the main thread and syncs the URL.
- [ ] Sidebar on `ThreadListPrimitive`, archive section, row actions through the thread-list item; remove page title generation.
- [ ] Keep project lists, drag-and-drop, history search and shared page working.
- [ ] Typecheck, lint, i18n, unit tests; Playwright `chat`, `chat-workspace`, `chat-history-search`, `chat-ui-polish`, `chat-sources-toolbar` with one worker. Record changed assertions in verification.
- [ ] `verification.md`; consolidate into specs/tests; register in `AGENTS.md`.
