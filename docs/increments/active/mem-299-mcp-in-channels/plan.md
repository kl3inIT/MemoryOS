# MEM-299 plan

[Design](design.md). Three pull requests, each behind CI.

## PR 1: MCP in channel turns (core and API)

- [x] V195:
  - `mcp_server.public_bots`, checked to need a shared connection or none.
  - `mcp_server_persona (tenant_id, server_id, persona_id)`, deleted with either side.
- [x] `McpServerService`, create and update:
  - Take `publicBots` and `personaIds`.
  - Access is exactly one of: organization-wide, Groups, assistants.
  - `publicBots` is refused (`MCP_INVALID`) unless the server uses a shared connection or none.
  - Assistants must be custom and not deleted.
  - Audited with the server.
- [x] `ChatPersonaService`: an assistant-only server is refused on any other assistant (`CHAT_INVALID_REQUEST`), unless
  it attached the server before. The composer's accessible set is unchanged, so the server is in no one's composer.
- [x] A turn's assistant carries the servers open through it (`Persona.mcpOpenThrough`), also among its servers.
- [x] `McpTurnService.open` takes those servers and a surface (web, a member's channel, a bot's channel, a public bot):
  - **Through the assistant:** open for anyone who uses it, and always on.
  - **Any channel:** every attached server is selected; `ASK` tools are left out.
  - **The bot's account:** `PER_USER` servers are reported `PERSONAL`.
  - **A public bot:** only `publicBots` servers, and only `READ` tools.
- [x] `ChatTurnService`: a channel turn resolves the assistant's servers, with the surface from `ChatChannel`, and opens
  no Connect card.
- [x] `McpTools.unavailableNotice`: channel wording and `PERSONAL`.
- [x] API:
  - `McpServerRequest` takes optional `personaIds` and `publicBots`.
  - `McpServerResponse` returns both.
  - `openapi.yml` and the hey-api client are regenerated.
- [x] Tests: `McpTurnServiceTest`, `McpToolsTest`, `ChatTurnServiceTest`, and
  `ChatSessionApiIntegrationTest.anMcpServerOpensThroughChosenAssistantsAloneAndToPublicBotsOnlyWithASharedConnection`.
- [x] Docs: the chat and channel specs, and the test matrices.

## PR 2: administration (web)

- [x] Server editor:
  - *Ai dùng được* offers *Cả tổ chức*, *Chọn nhóm* and *Chỉ qua trợ lý được chọn*, with an assistant picker.
  - *Cho bot công khai dùng*, for a shared credential or none.
  - The detail page shows both.
- [x] Assistant editor: the servers open through the assistant are shown, not chosen.
- [x] `PersonaView` names the servers open through the assistant and whether a public bot may use each.
- [x] *Công khai* dialog and the assistant editor's public-bot warning: the servers a public bot uses.
- [x] i18n (vi, en), component tests, screenshots at desktop and phone width, light and dark.
- [x] Docs: the web parts in the chat and channel specs.

## PR 3: the assistant of a member's direct messages

- [ ] V196: `chat_preferences.channel_persona_id` (nullable, cleared when the assistant is deleted).
- [ ] Preferences:
  - `PUT` accepts an assistant the member may use and refuses another.
  - Reading it back drops one that is no longer usable.
- [ ] Channel: a member's direct message to a bot that is not public answers with it, else the bot's default.
  Changing it starts the conversation over (`ChannelTurns.continues` already compares the assistant).
- [ ] Settings › Chat: *Trợ lý khi nhắn riêng bot*, a picker of the member's assistants with the bot's default first.
  It saves on change.
- [ ] Tests:
  - Preferences validation.
  - `ChannelInboundServiceTest`:
    - The member's choice answers their direct message.
    - A group or a public bot ignores it.
    - An unusable choice falls back to the bot's default.
  - Web component test.
- [ ] Docs: the chat spec's personal preferences and the channel spec's direct message.

## Acceptance on staging

- [ ] An administrator adds Linear with a shared read-only token, open only through the assistant "CSKH":
  - [ ] Another member cannot select it on the web.
  - [ ] The bot answering with "CSKH" uses it in a group.
- [ ] With the bot public:
  - [ ] Before *Cho bot công khai dùng* is on, the server is not used.
  - [ ] After it is on, the server is used, read tools only.
- [ ] A member connects Gmail on the web, picks their assistant, and asks the bot in a direct message to summarise
  today's mail:
  - [ ] It reads their mail.
  - [ ] Another member's direct message does not.
- [ ] A personal server not connected: the bot tells the member to connect it in MemoryOS.
