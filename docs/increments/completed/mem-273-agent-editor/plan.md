# Implementation plan

Design: [design.md](design.md). Four pull requests, each accepted when CI is green and staging has deployed it; the
owner checks the editor on staging after the third.

## 1. Fields

- [x] Migration V181: add `title` (80), `greeting` (500), `reasoning_effort`; append a non-empty `task_prompt` to
  `instructions` after a blank line; drop `task_prompt`, `knowledge_cutoff`, `context_token_limit`,
  `output_token_limit`, `replace_base_system_prompt`.
- [x] Core: `PersonaEntity`, `ChatPersonaService` validation and views, `JdbcChatRepository.persona` and
  `ChatTurnOptions` without the removed fields; `ChatModelExecutor`, `ChatModelGuard`, `ChatTurnSetup` and
  `ChatTurnPersistence` without task prompt, cutoff and per-assistant limits; effort resolved session → assistant →
  member default → model.
- [x] Defaults: a new assistant gets search and web search only.
- [x] Greeting and title on the empty conversation (`ChatThread` empty state) and on cards.
- [x] Current editor: add title, greeting and effort (`ModelSelector` efforts); remove the five fields.
- [x] Contract: `openapi.yml`, Hey API client.
- [x] Tests: migration folds a task prompt; effort resolution order; the removed fields no longer reach the prompt;
  persona API round trip of the new fields.

## 2. Editor

- [x] Shell: tabs with `?tab=` (Cấu hình, Chia sẻ), sections per design decision 11. No sticky section nav (design
  decision 10). The
  `ResizablePanelGroup` arrives with the test chat in step 3 and the Kênh tab with binding in step 4, so neither ships
  empty ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).
- [x] One header: the breadcrumb, state, Hủy and Lưu go into the shell header (`AppShellHeader` `breadcrumb` and
  `actions`); the editor's own sticky bar is gone.
- [x] Save stays on the page; disabled-reason tooltip; blocker kept, tab switches not blocked. Labels save on change in
  the Chia sẻ tab; an uploaded image still applies only on Save.
- [x] Unified document picker with the heading switch; "Chỉ trả lời có căn cứ" as one switch under it.
- [x] Ứng dụng MCP hidden when the organization has no MCP server; no explanatory line in the create dialog; the
  sources note in Chia sẻ is one muted line under Quyền truy cập chung.
- [x] Create dialog; `/agents/create` opens it; create then opens the editor.
- [x] Chia sẻ tab from the share dialog body, with labels and the admin listing switches, saving on change; the card's
  share action opens the tab.
- [x] Remove `AgentPreview`, the share dialog and the new-assistant local draft.
- [x] i18n; tests for tabs, save states, picker, sharing revision, create flow.

## 3. Test chat

- [x] Backend: `draftPersona` on the temporary send command, editor-only, validated as Save, no revision check.
- [x] Web: a single-thread runtime with its own transport inside the editor; greeting, starters, "đang dùng bản chưa
  lưu", "Làm mới"; a `Drawer` below `lg`, a `ResizablePanelGroup` from `lg` up. `ChatThread` follows its own width
  with container queries, so the panel reuses it unchanged.
- [x] Tests: a draft changes the answer without changing the stored assistant; a non-editor is refused; a draft
  source the editor cannot read is refused.

## 4. Channels

- [x] Migration V183 (V182 was taken): Chat's `chat_channel_binding` (bot → assistant, removed with the assistant)
  and `chat_session.channel_connection_id`; a channel turn uses the assistant only while its bot is still bound.
- [x] `ChannelTurns` uses the bound assistant, else the built-in one.
- [x] `PUT /api/channels/{id}/persona`, channel responses with the assistant, `GET /api/chat/personas/{id}/channels`;
  `CHANNELS_MANAGE` plus edit rights to bind.
- [x] Kênh tab: list, add, move with confirmation, remove; the admin "Kênh chat" page shows each bot's assistant.
- [x] Tests: a bound bot answers with its assistant; deleting the assistant falls back; binding without
  `CHANNELS_MANAGE` is refused.

## 5. One bot, one assistant per group

Design: [design-groups.md](design-groups.md). Three pull requests.

- [x] 5a. Save and Cancel only on the Cấu hình tab; Kênh and Chia sẻ keep the unsaved-changes state (decision 9).
  [#738](https://github.com/kl3inIT/MemoryOS/pull/738).
- [x] 5b. Backend: one migration (V185) with `channel_groups`, `chat_channel_group_binding` and
  `chat_session.channel_chat_id`; groups recorded on an accepted group message, Lark's name from the chat read the
  external check makes; resolution group → bot → built-in in `channelPersona` and `CHANNEL_OR_USES`; the group API and
  `GET /api/chat/personas/{id}/channels` with groups; permissions and audit as decision 6. Tests: each resolution
  branch; changing, removing and forgetting end access and start the group's conversation over; a direct message
  ignores group overrides; permission refusals; an external Lark group is not recorded.
- [x] 5c. Web: the bot page's "Trợ lý mặc định" picker and "Theo nhóm chat" list; the Kênh tab read-only with links to the
  bot pages. Unit and e2e tests; staging screenshots; channel and chat specs, test matrices.
- [x] 5d. Lark groups known on joining ([design-groups decision 9](design-groups.md#step-5d-lark-groups-known-on-joining)):
  `ChannelReceiver.joined`, `ChannelAdapter.groups`, `ChannelGroupRoster` with the supervisor's 10-minute listing,
  V187 (`channel_groups.last_message_at` nullable), the Lark adapter's `botAdded` and paged `im/v1/chats`; the page's
  "chưa có ai hỏi" and no "Quên nhóm" on Lark; the dialog and runbook name `im.chat.member.bot.added_v1`. Tests:
  roster (join, list adds, renames, removes with the override), supervisor (listed on connect, not again before 10
  minutes, joined passed on), Lark paging and filters, unit and e2e. [#758](https://github.com/kl3inIT/MemoryOS/pull/758);
  the empty "Chưa có nhóm chat nào" line and the shorter Kênh tab subtitle in
  [#769](https://github.com/kl3inIT/MemoryOS/pull/769). The live check in a Lark group needs the app owner to add the
  event first and moved to [MEM-296](https://linear.app/memory-os/issue/MEM-296).

## 6. An app the person has not connected (Q12)

Design: decisions 19–23. One pull request.

- [x] `McpTurnService.open` takes the assistant's servers; `INACCESSIBLE` joins the reasons; the notice goes in the
  final reminder even with no MCP tool left; the connectable ones are published as `mcp_unavailable` and kept in the
  reply's activity. Channel and Deep research turns report nothing.
- [x] Web: the card under the answer with `ConnectAction`, live and from history; it follows the app's state now.
- [x] Tests: `McpTurnServiceTest` (each reason, a usable server turned off, a selected server not attached),
  `McpToolsTest` (each reason's sentence), `ChatWebPromptsTest` (reminder, not system), `ChatTurnServiceTest`
  (attached servers opened, card event and stored activity), transport and card unit tests, e2e with screenshots.
- [x] Staging: the probe of 2026-10-18 again (a member without Linear asks the Linear assistant); the answer names
  the missing connection and Connect starts the provider's sign-in
  ([verification](verification.md#q12-an-app-the-person-has-not-connected)). The return after a completed sign-in was
  not exercised, because the probe does not sign in to Linear.

## Verification

- [x] On zm, throttled: the persona, chat and channel tests, `ModulithArchitectureTest`, `CoreDependencyRulesTest`,
  `OpenApiContractTest`, run for each pull request; CI ran the full gates before every merge.
- [x] Staging: create through the dialog, edit and test without saving, save and reopen, share tab, a bot's assistant
  and group chats on the bot page; screenshots in light and dark, desktop and phone
  ([verification](verification.md)). A question to a live Zalo bot was not part of these runs.
- [x] Docs: the chat spec (assistant fields, editor, Q12), the channel spec (the bot's assistant, group chats), the test
  matrices, the roadmap.
