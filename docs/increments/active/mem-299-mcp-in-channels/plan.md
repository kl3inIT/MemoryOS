# MEM-299 plan

[Design](design.md). Two pull requests, each behind CI.

## PR 1: core and API

- [ ] V195: `mcp_server.public_bots BOOLEAN NOT NULL DEFAULT FALSE`.
- [ ] `McpServerService`: create and update take `publicBots`. The value is refused (`MCP_INVALID`) unless the server
  uses a shared credential or none. Changing the server to personal credentials clears it. Audited with the server.
- [ ] `McpTurnService.open` takes a channel mode (none, bot, public bot):
  - In any channel, `ASK` and `OFF` tools are left out.
  - On the bot's account, `PER_USER` servers are reported unavailable and not opened.
  - For a public bot, only servers with `public_bots` open, and only their `READ` tools.
- [ ] `ChatTurnService`: a channel turn resolves the assistant's attached servers (no Deep research, model with tool
  calling), selects all of them, and passes the mode from `ChatChannel`. A channel opens no Connect card.
- [ ] `McpTools.unavailableNotice`: channel wording that names *Cài đặt › Kết nối* instead of the button.
- [ ] `PersonaView.mcpServers` refs carry `publicBots`.
- [ ] API:
  - `McpServerRequest` and `McpServerResponse` gain `publicBots`.
  - `openapi.yml` and the hey-api client regenerated.
- [ ] Tests:
  - `McpTurnService`:
    - A member's direct message uses their own connection.
    - A group turn on the bot's account opens a shared-key server, skips a personal one, and leaves out `ASK` tools.
    - A public bot opens only marked servers and only their read tools.
  - Server administration: the flag is refused on a personal-credential server and cleared on a switch to personal
    credentials.
  - `ChatTurnService` or the channel-turn integration test: a channel turn passes the attached servers.
  - The notice's channel wording.
  - API contract test.
- [ ] Docs:
  - Chat spec: the MCP section and the channel exception.
  - Channel spec: a public bot's MCP.
  - Test matrices.

## PR 2: web

- [ ] Server editor: *Cho bot công khai dùng* under *Ai dùng được*, for a shared credential or none. The detail page
  shows it among the facts.
- [ ] *Công khai* dialog: each assistant's servers that a public bot uses.
- [ ] Assistant editor: the public-bot warning also names those servers.
- [ ] i18n (vi, en), component tests, screenshots at desktop and phone width, light and dark.
- [ ] Docs: the web parts in the chat and channel specs.

## Acceptance on staging

- [ ] The administrator connects a read-only GitHub or Linear server with a shared key, and the bot's assistant
  attaches it.
- [ ] A group question on the bot answers from the tool.
- [ ] With the bot public:
  - [ ] Before the switch is on, the server is not used.
  - [ ] After it is on, the server is used, read tools only.
- [ ] A linked member's direct message with a personal server not connected: the bot tells them to connect it in
  MemoryOS.
