# Plan

## A. Archive (withdrawn)

Implemented in `91f3329`, then withdrawn on 2026-09-13 by the owner after UI review: Onyx has no archive, the sidebar already offers projects, search and delete, and an archive entry point in the sidebar defeats its purpose. V51, the archive endpoints and the `status` list filter were removed before any push, so no migration was published.

## B. Web runtime migration

- [x] Thread-list adapter with history adapter; unit tests with a fake API.
- [x] Per-thread controller registry; the transport resolves thread initialization when it creates the session.
- [x] Mount the list runtime under the authenticated route; `ChatPage` renders the main thread and syncs the URL.
- [x] Sidebar on thread-list state, row actions through the thread-list item; page title generation removed.
- [x] Keep project lists, drag-and-drop, history search and shared page working.
- [x] "Search conversations" as a sidebar row under "New conversation" with Ctrl/⌘+K.
- [x] Typecheck, lint, i18n, unit tests; Playwright chat suites with one worker.
- [x] `verification.md`; consolidate into specs/tests; register in `AGENTS.md`.
- [ ] Whole-repository `clean check` before the pull request.
