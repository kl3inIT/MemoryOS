# MEM-273 Assistants and their topics

The sidebar becomes a person's list of assistants. Every conversation with an assistant is one of its topics. Opening
an assistant turns the left sidebar into that assistant's topics, as LobeHub's agent sidebar does. A "Gần đây" section
keeps the latest topics across assistants, and a history page lists them all. This is the second step of
[MEM-273](https://linear.app/memory-os/issue/MEM-273), after [agent avatars](../mem-273-agent-avatars/design.md). The
explore page with Fork (step 3) and binding channel bots to assistants (step 4) build on it.

The owner approved the layout on 2026-10-16 from the prototype reviewed in chat (assistant sidebar on the left,
projects above assistants). The decisions below record it with the references that support each one.

## Today

- The sidebar (`web/src/features/chat/session/chat-navigation.tsx`) shows "Hội thoại mới", Search documents, "Trợ
  lý", Meetings and Library, then the pinned assistants, Projects and "Hội thoại gần đây". When collapsed, only the top
  links remain.
- Clicking a pinned assistant always creates a new session (`PinnedAgents.start`) and opens it. A plain `/` creates
  its session on the first send, with the tenant's default persona (`runtime/chat-transport.ts`).
- Every `chat_session` has a `persona_id`; a session created without one gets the default persona. Old plain chats
  are therefore already conversations with the built-in MemoryOS assistant.
- `GET /api/chat/sessions` pages a person's web sessions by `updated_at` with no assistant filter; `GET
  /api/chat/sessions/search` has none either. Neither returns a preview or whether a reply is running.
- Pinning stores `actor_pinned_persona` (V74, at most 100, ordered). The first read seeds the featured public
  assistants once (`JdbcAgentRepository.seedPinsOnce`). The built-in assistant cannot be pinned. A pin whose assistant
  is gone or no longer usable is dropped when read (`ChatPersonaService.pins`).
- Rows already have rename, share, move to project, archive and delete (`chat-session-menu.tsx`).
- `updated_at` also moves on rename, title generation, persona change and project moves.

## How the references do it (code read 2026-10-16)

LobeHub paths are under `tmp/refs/lobe-chat` (lobehub/lobehub, cloned 2026-10-15).

| | LobeHub | Rakazo | OpenClaw / Hermes | ChatGPT |
| --- | --- | --- | --- | --- |
| Conversations of an assistant | Topics per agent (`packages/database/src/schemas/topic.ts`) | One thread per bot (`Thread.botId @unique`) | One main session; `/new` starts another | Chats per GPT or project |
| Where the topics are | The left sidebar turns into the agent's sidebar at `/agent/:aid` (`features/NavPanel/routeKey.ts`, `features/AgentSidebar`) | — | Threads list beside the main session | Project page lists its chats |
| Back to the home sidebar | Back button to `/` (`SideBarHeaderLayout.tsx`, `components/BackButton.tsx`); home stays mounted | — | — | — |
| Opening an assistant | Opens an empty new topic (`useAgentIdStoreSync.ts:72-79`) | Opens its one thread | Opens the main session | Opens a new chat |
| New topic | No row until the first send; title from the first message, then generated (`topic/action.ts:336-357`, `conversationLifecycle.ts`) | — | `/new` | No row until the first send |
| Topic grouping | By time: Today, Yesterday, This week, This month, then months and years, by last activity (`packages/utils/src/client/topic.ts`) | — | — | Today, Yesterday, 7 days, 30 days |
| Topic rows | Title only; running ring; no timestamp (`AgentSidebar/Topic/List/Item`) | — | — | Title only |
| Long topic lists | First 20, then "View all topics" to `/agent/:aid/topics` (`GroupedAccordion.tsx`) | — | — | Scroll |
| Search in the agent sidebar | Opens the global command menu (`Header/Nav.tsx`) | — | — | Global search |
| Recent across assistants | "Recents", 5 by default, type icons, "More" drawer of 50 (`features/Home/Recents`) | — | — | "Chats", every chat |
| Home section order | Recents, then Projects, then Agents (`systemStatus.ts:195-208`); customisable | — | — | Projects above Chats |
| Agent rows | 22px avatar, name, running badge, unread count; no preview | Avatar, last message, unread | — | — |

## Decisions

1. **A topic is a `chat_session`.** No new table and no new kind of session. A topic of an assistant is a web session
   with that `persona_id`, so every existing conversation is already a topic of its assistant, and old plain chats
   are topics of MemoryOS. Project conversations stay topics of their assistant too, as in LobeHub, and still show
   under their project. Channel sessions stay off the web lists until step 4.
2. **The list of assistants is the pins.**
   - "Thêm" pins, as today. `actor_pinned_persona`, its order, the seeding and the 100 limit stay.
   - The built-in MemoryOS assistant is always the first row and cannot be removed.
   - A pin whose assistant became unusable, deleted or unshared is kept and shown as "Không còn dùng được"; its row
     still opens the person's existing topics read-only. Today such pins are silently dropped.
   - Rows reorder by drag, as they do now. Removing a row unpins it; its topics stay in history.
3. **Opening an assistant opens its latest topic.** It differs from LobeHub, which opens an empty topic, and follows
   Rakazo, OpenClaw and Hermes, where the person returns to where they left off (the owner's choice). With no topic
   yet, the assistant opens an empty new topic with its greeting and starters.
4. **The assistant sidebar**, after LobeHub's `AgentSidebar`, replaces the home sidebar while an assistant's topic is
   open:
   - a back button to the home sidebar, the avatar, the name and the owner (or "Fork từ X");
   - "Chủ đề mới", "Tìm kiếm" (the existing Ctrl/⌘K search, filtered to this assistant) and "Thông tin trợ lý";
   - the topics grouped Hôm nay, Hôm qua, Tuần này, Tháng này, then by month and by year, by last activity, as
     LobeHub's buckets;
   - each row shows its title and a running ring; the row menu is today's (share, rename, move to project, archive,
     delete);
   - the first 20 topics, then "Xem tất cả" to the history page filtered to this assistant.

   The sidebar follows the URL: `/chat/$sessionId` of a session outside a project shows its assistant's sidebar;
   the home, projects, search, library, meetings and agents pages show the home sidebar. Going back keeps the open
   page, as LobeHub keeps home mounted.
5. **New topic writes nothing until the first send**, as LobeHub and today's `/`. The title is the first message,
   then the generated title, as today. No summary is carried into a new topic (the owner's decision; LobeHub and
   OpenClaw do the same).
6. **No automatic topic on the web.** Topics change only when the person asks, as in OpenClaw's default, Hermes and
   LobeHub; compaction handles length. Zalo and Lark groups keep their 4-hour idle conversation (MEM-263).
7. **Every topic stays open.** It can be continued, edited, regenerated, branched, shared, archived and deleted, as
   any session today.
8. **The home sidebar**, from top to bottom:
   - the brand, the search and the collapse button;
   - the tools: Tìm tài liệu, Cuộc họp, Thư viện;
   - "Dự án" with "+", as today's projects section;
   - "Trợ lý" with "+" (opens the explore page): each row has the avatar, the name, the time of the last message, a
     one-line preview of it, and "Đang trả lời…" with a ring around the avatar while a reply runs; then "Khám phá trợ
     lý";
   - "Gần đây": the 8 latest topics across assistants, each with a small avatar, title and time, then "Xem tất cả".
     LobeHub shows 5 with type icons; the avatar tells assistants apart, which is what this list is for.
   - the account menu.

   "Hội thoại mới", the "Trợ lý" menu entry and "Hội thoại gần đây" are removed: "Chủ đề mới" and the MemoryOS row
   replace the first, "Khám phá trợ lý" the second and "Gần đây" the third. The collapsed rail shows the tools and the
   assistants' avatars.
9. **Search (Ctrl/⌘K)** stays a centered dialog, as LobeHub's command menu (`features/CommandMenu`) and ChatGPT's
   chat search. The owner chose this scope on 2026-10-16:
   - the trigger is a "Tìm kiếm ⌘K" field at the top of the home sidebar, and "Tìm kiếm" in the assistant sidebar;
   - results in three groups, five each: assistants (by name and description), topics (title and messages, today's
     full-text search with its snippet) and projects (by name); a group header opens the rest;
   - opened from an assistant sidebar, a removable chip "Trong: <tên trợ lý>" filters topics to that assistant, as
     LobeHub sends the active `agentId`;
   - with no query, the recent topics and the "Chủ đề mới" action, as today;
   - documents stay in "Tìm tài liệu", which answers with citations and is a different search.
10. **The history page** at `/history` lists every topic, newest first, with the existing full-text search and a
   filter by assistant, and links each row to its topic. Archived topics stay on the existing archive page.
11. **Phones.** The sidebar is today's sheet. Opening a topic closes it; reopening it shows that assistant's sidebar,
    whose back button returns to the home sidebar. LobeHub's mobile topic modal shows the same list.
12. **Routes.**
    - `/assistants/$personaId` opens the latest topic (redirect to `/chat/$sessionId`) or the empty new topic.
    - `/assistants/$personaId/new` is the empty new topic of that assistant; its first send creates the session and
      replaces the URL with `/chat/$sessionId`.
    - `/` is the new topic of MemoryOS, as today.
    - `/chat/$sessionId` keeps working for every session.
    - `/history`, with `?assistant=` for the filter.
13. **Out of this step.**
    - Unread markers (with schedules, MEM-265) and favourite topics.
    - Fork and the explore page (step 3); direct messages from Zalo and Lark joining an assistant's topics (step 4).
14. **Staging acceptance (2026-10-16): empty topics and a sidebar that fits at 900px.** The owner chose both fixes.
    - A web conversation becomes a topic with its first question, as a LobeHub topic and a ChatGPT chat exist only
      after the first message (decision 5). The session lists and search, a Project's conversations, the assistants'
      latest topic, "Gần đây", `/history` and the data export leave out a session without a USER message, through an
      `EXISTS` that `ix_chat_message_parent` (`session_id` first) answers; `/chat/$sessionId` still opens it. V180
      soft-deletes the web, non-temporary ones older than a day, which the earlier sidebar created on each click on
      an assistant (45 on staging), and the retention purge removes them. A conversation holds no upload of its own and
      one without a question has no answer, so nothing else is lost.
    - "Dự án" lists its first three projects; "Xem tất cả dự án" follows only when more exist, as ChatGPT's "See more",
      which changes the projects increment's always-shown link (the owner's choice). The headings of "Dự án", "Trợ lý" and "Gần đây" fold their sections, as
      LobeHub's sidebar sections fold, and each choice is kept per browser in `localStorage`. Assistant rows lose 4px
      of padding and the sections 4px of gap, so with two projects and five assistants the first "Gần đây" row shows
      at 1440×900 without scrolling.
    - "Trợ lý" shows five rows, MemoryOS and the first four pins, then "Xem thêm (n)", which opens the rest in place, and
      "Thu gọn", as LobeHub's HomeSidebar agent list (`agentPageSize` 5, `features/HomeSidebar/Body/Agent`); LobeHub's
      "more" opens a drawer, here the list grows in place, which keeps drag and keyboard reordering in one list. The
      section's "…" menu, "Hiển thị", picks 5, 10, 15 or 20 rows, as LobeHub's page-size submenu, kept per browser. The
      open assistant and one whose reply runs always keep their row. A reorder of the shown pins puts them into the
      places they held among all pins, as the Projects section does. With seven pins the first "Gần đây" row still
      shows at 1440×900: "Xem thêm (3)" takes the room "Xem tất cả dự án" gave back.

## Deviations in the web delivery

Recorded while building the sidebar (PR 2b); each follows a reference or a constraint found on the way.

- **"7 ngày qua" instead of "Tuần này".** LobeHub's `week` bucket is the last seven days before yesterday, not the
  calendar week, so the label says what it holds. The other buckets are LobeHub's.
- **The assistant sidebar has no inline topic filter.** "Tìm kiếm" opens the search dialog with the "Trong:" chip,
  which already searches the messages; a second, title-only filter would answer the same question worse.
- **MemoryOS's new topic keeps the default welcome.** Only another assistant's new topic shows its avatar,
  description and starters (`AssistantWelcome`); `/` looks as it did.
- **No "Chủ đề mới" placeholder row in the topic list.** LobeHub draws one; here the highlighted "Chủ đề mới" link is
  the same signal, and a placeholder row would move the list while it is created.
- **Reordering and unpinning are hover controls, so phones do neither in the sidebar.** The explore page and the
  assistant's detail still pin and unpin on touch; drag on a scrolling phone sheet fights the scroll.
- **A gone assistant's topic is read-only.** The composer is hidden when the conversation's assistant can no longer
  be loaded, as decision 2 requires; the default assistant is never gone.
- **Removing a topic from its Project swaps the sidebar to its assistant**, because the sidebar follows the open
  topic (decision 4); Back returns to the home with the Projects.
- **The header search icon is gone.** The sidebar's "Tìm kiếm" row with its shortcut replaces it, as in LobeHub.

## API

- `GET /api/chat/assistants` returns the sidebar rows in pin order, MemoryOS first. Each row has the assistant's
  name, description, avatar fields, `builtin`, `ownerName`, `usable`, and the caller's latest web topic of it: id,
  title, last message time, a plain-text preview of the last question or finished answer (at most 120 characters)
  and whether a reply is running. The rows come from a fixed number of statements (the latest topics in one, with a
  lateral join per assistant). An unusable row carries only the name and face. The fork origin waits for step 3,
  which adds forks. `PUT /api/chat/persona-pins` still reorders, and keeps an unusable pin the list still names.
- `GET /api/chat/sessions` and `GET /api/chat/sessions/search` take an optional `personaId`. They list the caller's
  own sessions only, so the filter needs no access check on the assistant: an unusable assistant's row still opens
  its topics (decision 2).
- Every session read (`ChatSessionResponse`) gains `replyRunning`, so topic rows and "Gần đây" draw the ring.
- V178: an index on `chat_session (tenant_id, owner_actor_id, persona_id, updated_at DESC, id)` for active web
  sessions, for the per-assistant list and the latest topic.
- The search dialog needs no new endpoint. Its assistant group reads the existing `GET /api/chat/personas?q=`,
  which already matches name and description; its project group filters, in the browser, the projects the sidebar
  already loads (`GET /api/chat/projects`, a person's own and few), as the Projects page search does.

## Reuse

- Web: the shadcn `Sidebar` primitives and the app shell's sheet; `SidebarLink`; `SortableList` for pin order;
  `AgentAvatar` from step 1; `ChatSessionRow` and `ChatSessionMenu` for topic rows; `ChatHistorySearch` for search;
  `groupThreadTitles` extended to LobeHub's buckets; the assistant-ui thread list stays the runtime's source of the
  open topic.
- Backend: `ChatSessionService` paging and search; `hasActiveReply`'s query as a column; `ChatPersonaService` views
  and access checks.

## Not in scope

- One conversation per assistant, a primary conversation and topic boundaries inside a session (the earlier model of
  this design, replaced on 2026-10-16).
- Group chats with several assistants, delegation between assistants.
- Temporary chats change in no way.

## Risks

- **People lose "Hội thoại mới".** The MemoryOS row and "Chủ đề mới" in every assistant sidebar replace it; `/` still
  starts a new MemoryOS topic. The release note says where conversations went.
- **MemoryOS collects every old chat.** Its sidebar shows 20, grouped by time; "Xem tất cả" and the filtered history
  page reach the rest.
- **The sidebar changes under the person.** It changes only on navigation, and the back button is always at the same
  place.
