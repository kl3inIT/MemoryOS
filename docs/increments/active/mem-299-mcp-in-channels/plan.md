# MEM-299 plan

[Design](design.md). Three pull requests, each behind CI.

## PR 1: MCP in channel turns (core and API)

- [ ] V195:
  - `mcp_server.public_bots BOOLEAN NOT NULL DEFAULT FALSE`.
  - `mcp_server_persona (tenant_id, server_id, persona_id)`, deleted with either side.
- [ ] `McpServerService`, create and update:
  - Take `publicBots` and `personaIds`.
  - `publicBots` is refused (`MCP_INVALID`) unless the server uses a shared credential or none. A switch to
    personal credentials clears it.
  - A persona must be a custom assistant of the Tenant.
  - Audited with the server.
- [ ] `McpAccessRepository`: a server open through an assistant. The composer's accessible set is unchanged.
- [ ] `ChatPersonaService`: an assistant-only server attaches only to its assistants (`CHAT_INVALID` otherwise). The
  editor's and the builder's choices list it only for them.
- [ ] `McpTurnService.open` takes the turn's assistant and a channel mode (none, bot, public bot):
  - **Access:** the actor's, plus servers open through the assistant.
  - **Any channel:** `ASK` and `OFF` tools are left out.
  - **The bot's account:** `PER_USER` servers are reported unavailable.
  - **A public bot:** only `public_bots` servers, and only their `READ` tools.
- [ ] `ChatTurnService`: a channel turn resolves the assistant's attached servers when the model calls tools and the
  turn is not Deep research, selects all of them, and takes the mode from `ChatChannel`. It opens no Connect card.
- [ ] `McpTools.unavailableNotice`: channel wording.
- [ ] `PersonaView.mcpServers` refs carry `publicBots`.
- [ ] API: `McpServerRequest` and `McpServerResponse` gain `publicBots` and `personaIds`. Refresh `openapi.yml` and
  the hey-api client.
- [ ] Tests:
  - `McpTurnService`:
    - A member's direct message uses their own connection.
    - A group turn on the bot's account opens a shared-key server, skips a personal one, and leaves out `ASK` tools.
    - A public bot opens only marked servers and only their read tools.
    - An assistant-only server opens through its assistant for anyone, and for no other assistant.
  - Server administration:
    - The public flag is refused on a personal server and cleared on a switch to personal credentials.
    - Persona grants are validated.
  - Persona: an assistant-only server is refused on another assistant.
  - The channel turn passes the attached servers.
  - The notice's channel wording.
  - The API contract test.
- [ ] Docs:
  - Chat spec: the MCP section and the channel exception.
  - Channel spec: MCP in a channel and on a public bot.
  - Test matrices.

## PR 2: administration (web)

- [ ] Server editor:
  - *Ai dùng được* offers *Cả tổ chức*, *Chọn nhóm* and *Chỉ qua trợ lý được chọn*, with an assistant picker.
  - *Cho bot công khai dùng*, for a shared credential or none.
  - The detail page shows both.
- [ ] Assistant editor: an assistant-only server is listed only for its assistants.
- [ ] *Công khai* dialog and the assistant editor's public-bot warning: the servers a public bot uses.
- [ ] i18n (vi, en), component tests, screenshots at desktop and phone width, light and dark.
- [ ] Docs: the web parts in the chat and channel specs.

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
