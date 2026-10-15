# Implementation plan

Design: [design.md](design.md). Two pull requests: the backend first, then the web. Each is accepted when CI is green
and staging has deployed it. The web pull request is accepted when the owner has checked the sidebar on staging.

## 1. Backend

- [ ] Migration V178 (V176 is held by MEM-270): the index on `chat_session (tenant_id, owner_actor_id, persona_id,
  updated_at DESC, id) WHERE deleted_at IS NULL AND archived_at IS NULL AND NOT temporary AND surface = 'WEB'`.
- [ ] `personaId` filter on the session list and the search, in `DefaultChatSessionService`, `JdbcChatRepository` and
  `JdbcChatSearchRepository`; the caller must be able to read the persona, or the list is empty.
- [ ] `replyRunning` on `ChatSessionResponse`, computed in the list, search and single-session reads from the running
  reply (`uq_chat_active_reply`).
- [ ] Pins keep an unusable assistant and return it marked `usable: false`; reordering keeps such pins; seeding is
  unchanged.
- [ ] `GET /api/chat/assistants` in `ChatPersonaController`, from `ChatPersonaService`:
  - in pin order, the built-in assistant first;
  - each row with the assistant's view, `usable`, and the latest web topic (id, last message time, preview, running);
  - the preview is plain text of the last user or assistant message, at most 120 characters;
  - one query for the rows, with a lateral join per assistant.
- [ ] Contract: refresh `openapi.yml` and regenerate the Hey API client.
- [ ] Tests at the PostgreSQL boundary:
  - the list and the search filtered by assistant return only its topics, and nothing for an assistant the caller
    cannot read;
  - `replyRunning` is true while a reply runs and false after;
  - an unshared assistant's pin is kept, marked unusable, and survives a reorder;
  - the assistants list puts MemoryOS first, follows pin order, shows each latest topic and preview, and reads in a
    fixed number of queries;
  - API test of `GET /api/chat/assistants` and the `personaId` parameter.

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
