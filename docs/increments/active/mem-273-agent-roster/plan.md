# Implementation plan

Design: [design.md](design.md). Two pull requests: the backend first, then the web. Each is accepted when CI is green
and staging has deployed it. The web pull request is accepted when the owner has checked the sidebar on staging.

## 1. Backend

- [x] Migration V178 (V176 is held by MEM-270): the index on `chat_session (tenant_id, owner_actor_id, persona_id,
  updated_at DESC, id) WHERE deleted_at IS NULL AND archived_at IS NULL AND NOT temporary AND surface = 'WEB'`.
- [x] `personaId` filter on the session list and the search, in `DefaultChatSessionService`, `JdbcChatRepository` and
  `JdbcChatSearchRepository`. It lists the caller's own sessions only, so it checks no access on the assistant: an
  unusable assistant still opens its topics (design decision 2; the earlier "empty unless readable" wording
  contradicted it).
- [x] `replyRunning` on every session read (`ChatSession`, `ChatSessionResponse`), from the running reply
  (`uq_chat_active_reply`) in the same statement as the session.
- [x] Pins keep an unusable assistant: `PUT /api/chat/persona-pins` keeps a pin the list still names and never pins an
  unusable assistant anew; `GET /api/chat/assistants` returns it marked `usable: false`. `GET /api/chat/persona-pins`
  still lists usable pins only, so the web's pin queue must move to the assistants list before it writes. Seeding is
  unchanged.
- [x] `GET /api/chat/assistants` in `ChatPersonaController`, from `ChatPersonaService.assistants`:
  - in pin order, the built-in assistant first;
  - each row with name, description, avatar fields, `builtin`, `ownerName`, `usable`, and the latest web topic (id,
    title, last message time, preview, running);
  - the preview is plain text of the last question or finished answer, at most 120 characters, without code blocks,
    link targets, citation markers or Markdown marks;
  - a fixed number of statements, the latest topics in one with a lateral join per assistant.
- [x] Contract: refresh `openapi.yml` and regenerate the Hey API client; web fixtures gain `replyRunning`.
- [x] Tests at the PostgreSQL boundary:
  - the list and the search filtered by assistant return only the caller's topics of it;
  - `replyRunning` is true while a reply runs and false after;
  - an unshared assistant's pin is kept, marked unusable, survives a reorder and cannot be pinned anew;
  - the assistants list puts MemoryOS first, follows pin order, shows each latest topic and preview, and reads the
    same number of statements with one more pin;
  - API test of `GET /api/chat/assistants` and the `personaId` parameter.
- [x] Verification on zm, throttled (`--cpus=3 --memory=4g`, `nice -n 19`): `ChatPersistenceIntegrationTest` (51),
  `ChatLifecycleIntegrationTest` (5), `ChatExportIntegrationTest` (2), `ModelCatalogSelectionTest` (10),
  `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `ChatSessionApiIntegrationTest` (98, 4 skipped as on main),
  `OpenApiContractTest` written then checked against the committed file.

## 2. Web

- [ ] Home sidebar per design decision 8, in `chat-navigation.tsx`, with the shadcn sidebar primitives:
  - Projects moved above the assistants;
  - assistant rows from `GET /api/chat/assistants`: avatar, name, time, preview, running ring (respecting
    `prefers-reduced-motion`), the unusable state, drag to reorder, unpin;
  - "Gần đây": 8 topics with avatar, title and time, then "Xem tất cả";
  - remove "Hội thoại mới", the "Trợ lý" entry and "Hội thoại gần đây"; the collapsed rail shows assistant avatars.
- [ ] Assistant sidebar per design decision 4: header with back, avatar, name and owner; "Chủ đề mới", "Tìm kiếm",
  "Thông tin trợ lý"; topics from the session list filtered by `personaId`, grouped with LobeHub's time buckets
  (`groupThreadTitles`), 20 then "Xem tất cả"; rows reuse `ChatSessionRow` and `ChatSessionMenu` with the ring.
- [ ] Sidebar choice from the route: the assistant sidebar on `/chat/$sessionId` outside a project and on
  `/assistants/$personaId/new`; the home sidebar elsewhere; back returns to home without leaving the page.
- [ ] Routes: `/assistants/$personaId` (latest topic or new), `/assistants/$personaId/new`, `/history` with search
  and the assistant filter; the agents page "Trò chuyện" opens the assistant instead of creating a session.
- [ ] Pinned assistants no longer create a session on click; the first send creates it.
- [ ] Pin changes (`usePinUpdates`) take the current order from `GET /api/chat/assistants` (every row but the
  built-in one) instead of `GET /api/chat/persona-pins`, so a write keeps the unusable pins.
- [ ] Search dialog per design decision 9: the sidebar trigger, assistant, topic and project groups of five, the "Trong: <trợ lý>" chip, the empty state.
- [ ] Phones: the sheet shows the sidebar of the open page; choosing a topic closes it.
- [ ] i18n in natural Vietnamese; remove the strings of the removed entries.
- [ ] Tests:
  - the home sidebar orders Projects, Assistants, Recent, with MemoryOS first;
  - opening an assistant opens its latest topic and swaps the sidebar; back restores the home sidebar;
  - "Chủ đề mới" writes nothing until the first send;
  - topic groups follow the buckets;
  - an unusable assistant shows its state and opens its topics without a composer;
  - `/history` filters by assistant.

## 3. Verification

- [ ] On zm, throttled: the chat tests, `ModulithArchitectureTest`, `CoreDependencyRulesTest` and
  `OpenApiContractTest`. CI runs the full gates.
- [ ] Staging, with realistic data: old chats appear as MemoryOS topics; opening an assistant opens its latest topic;
  a new topic appears in the sidebar and in "Gần đây" after the first send; an unshared assistant turns its row
  unusable; history filters by assistant. Screenshots in light and dark, desktop and phone.
- [ ] Docs: the chat spec (assistants, topics, sidebar), the test matrix, the roadmap, and the release note on where
  conversations went.
