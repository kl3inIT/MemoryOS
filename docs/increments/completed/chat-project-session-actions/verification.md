# Verification

Local results (Vitest, jsdom, and Playwright on Chromium against a fresh fixture server):

- `chat-projects-section.test.tsx`: the *Hội thoại mới* item navigates from `/chat/{id}` to `/projects/{projectId}` and issues neither `POST /api/chat/sessions` nor `POST /api/chat/projects/{id}/sessions`.
- `chat-session-menu.test.tsx` and the move tests of `chat-projects-section.test.tsx` after `useRefreshChatSessions` takes the returned session.
- `chat-projects.spec.ts`: removal keeps the conversation in recent history, search and after reload with focus on the recent link; the menu item opens an unsent conversation, `GET /api/chat/projects/{id}/sessions` lists no new session until the first send, and the first-send session can be removed with its transcript kept.
- Playwright `chat-projects.spec.ts`: all 11 scenarios pass. A Project conversation shows in its Project folder and not in recent history until it is removed from the Project; the e2e server is restarted between runs because a failed scenario leaves its Projects behind.
- The i18n audit, Oxlint (warnings denied), oxfmt, `tsc -b` and the Vitest suite of `src/features/chat` (272 tests) pass. Knip, the route check and the full browser suite run on CI.
