# MEM-299: a bot's assistant uses its MCP servers

[MEM-299](https://linear.app/memory-os/issue/MEM-299). The owner agreed to this scope on 2026-10-22.

## Today

- An assistant attaches MCP servers (MEM-273). On the web a turn opens them through `McpTurnService.open` for the
  person:
  - The server must be organization-wide (`tenant_wide`) or granted to one of their Groups (`mcp_server_group`).
  - The credential is the shared one (`ADMIN`) or the person's own (`PER_USER`).
  - An `ASK` tool waits for the person's approval card ([chat spec](../../../specs/chat.md)).
- Attaching a server checks only that it exists (`ChatPersonaService`). Access is checked when the turn runs.
- A channel turn opens none of the assistant's servers: `ChatTurnService` resolves them only when
  `command.channel() == null`.
- Who a turn runs as:
  - A direct message to a bot that is not public runs as the linked member, with the bot's default assistant.
  - A group question, and every turn of a public bot (MEM-298), runs as the bot's `SERVICE` account. That account
    holds only the Basic Group ([identity spec](../../../specs/identity.md)).
- A server shared through one token can only be opened to the whole organization or to Groups of people. The bot's
  account belongs to no such Group, so a token meant for one bot ends up usable by everyone.

## How the references do it

Read on 2026-10-22.

- **Onyx** ([MCP docs](https://docs.onyx.app/admins/actions/mcp); source: `tools/tool_constructor.py`,
  `server/features/mcp/credentials.py`, `db/mcp.py`, `onyxbot/slack/handlers/handle_regular_answer.py`):
  - **Shared or personal key:** the admin chooses per server ("All users can share one service account"). A server
    with a shared key or no authentication needs no user to connect (`requires_user_authentication`).
  - **Who uses an agent's server:** access control decides who may attach the server to an agent
    (`user_can_access_mcp_server`). A custom agent's tools then run for anyone who can use the agent.
  - **Slack:** the bot runs a channel question as the anonymous user, with every tool of the agent. OAuth and
    pass-through tools refuse that user; shared-key and unauthenticated tools run.
  - **Approval:** approval policies apply only in Craft.
- **Dust** ([MCP servers](https://docs.dust.tt/docs/remote-mcp-server), [Slack](https://docs.dust.tt/docs/slack)):
  - **Servers:** an administrator adds them. A Bearer token makes "all the calls with the account authenticated to
    the server".
  - **One token for one place:** "Restrict this tool to skills" makes a tool usable only through the skills that
    reference it, hidden from direct selection.
  - **One token per person:** one server per person, in a restricted space.
  - **Slack:** admins link an agent to a channel. Anyone picks another agent per message with `@Dust ~agent-name`.
- **Copilot Studio**:
  - **Maker credentials:** a tool can use the maker's credentials instead of each user's. Microsoft names the
    oversharing risk and lets admins block it
    ([maker-provided credentials](https://learn.microsoft.com/en-us/microsoft-copilot-studio/configure-no-maker-authentication)).
  - **Open agents:** an agent with *No authentication* is open to anyone with the link, and the security scan warns
    before it is published ([security scan](https://learn.microsoft.com/en-us/microsoft-copilot-studio/security-scan)).
- **Intercom Fin:** a data connector that returns a customer's own data needs verified identity first
  ([FAQ](https://www.intercom.com/help/en/articles/9916507-data-and-actions-faqs)).

## Decisions

1. **The assistant's servers open in a channel, for the turn's actor.** Every server the assistant attaches is
   resolved for whoever the turn runs as. There is no composer, so each attached server counts as selected. Onyx
   carries an agent's tools into Slack in the same way.

   | Who asks | Runs as | Assistant | Servers that open |
   | --- | --- | --- | --- |
   | Member, direct message, bot not public | The member | Their own choice (decision 8), else the bot's default | Servers the member may use, with the shared credential or their own |
   | Group chat, bot not public | The bot's account | The group's, else the bot's default | Servers the bot's account may use, shared credential or none |
   | Anyone, public bot | The bot's account | As set on the bot | As for a group chat, only servers open to public bots, read tools only |

2. **A server can be open only through chosen assistants.** *Ai dùng được* gains a third choice besides *Cả tổ
   chức* and *Chọn nhóm*: *Chỉ qua trợ lý được chọn*, like Dust's "Restrict this tool to skills". An administrator
   picks the assistants (`mcp_server_persona`):
   - The server is then usable only through those assistants, by anyone who may use them: a person on the web, or a
     bot that answers with one.
   - It is not offered in the composer, and only those assistants may attach it. The editor lists it only for them,
     and saving any other assistant with it is refused.
   - A token meant for one bot is given to that bot's assistant alone. The schema allows Groups and assistants
     together; the editor offers the three choices one at a time.
3. **Otherwise access stays the actor's.** Unlike Onyx, an attached server is not usable by everyone who uses the
   assistant, unless it is open through that assistant (decision 2). The bot's account may use an
   organization-wide server, one granted to the Basic Group, or one open through its assistant.
4. **No personal credential on the bot's account.** Nobody connects as the bot, so a `PER_USER` server never opens
   on the bot's account, and the model is told it is unavailable here. In a member's direct message, the member's own
   credential is used, as on the web. No product lends one person's Gmail to others except Copilot Studio's maker
   credentials, which Microsoft lets admins block.
5. **A public bot uses only servers an administrator opened to it.** A new switch on each server, *Cho bot công khai
   dùng* (`mcp_server.public_bots`, off by default), is offered only for a server with a shared credential or none.
   Changing the server to personal credentials turns it off. Changing it is audited with the server. Onyx and Dust
   have no such step: their Slack channels hold the company's own workspace, while a public bot answers strangers on
   Zalo.
6. **A public bot offers only read tools.** For a public bot, only tools whose risk is `READ` (MEM-264) open, even if
   a write tool is set to `ALWAYS`.
7. **A channel offers no tool that asks first.** A channel has no approval card, so an `ASK` tool is not offered at
   all. Offering it would only lead to a declined call.
8. **Each member picks the assistant of their direct messages.** Settings › Chat gains *Trợ lý khi nhắn riêng bot*
   (`chat_preferences.channel_persona_id`, empty by default), and this choice is MemoryOS's own:
   - **Where it applies:** a member's direct messages to every bot that is not public. Not to a group chat or a
     public bot, where the assistant stays the administrator's choice.
   - **What it may be:** an assistant the member may use. If it is deleted or no longer usable, the bot's default
     answers.
   - **Changing it** starts the conversation over, as changing the bot's assistant already does.
   - **Why:** Dust lets a person pick an agent per message (`~agent-name`), but Zalo and WhatsApp have no picker, so
     a per-person default fills that role. With the member's own connections (decision 4), this is the member's
     personal bot, for example one that reads their Gmail, without creating a bot.
9. **Notices name where to connect, not a button.** In a channel, `McpTools.unavailableNotice` tells the model there
   is no button:
   - A server not connected, or whose sign-in expired, is connected in MemoryOS under *Cài đặt › Kết nối*.
   - One unavailable to the bot's account only an administrator can open.

   A channel shows no tool steps and no MCP App views.
10. **What a public bot exposes is shown before it is switched on.** The *Công khai* dialog lists, beside each
    assistant's documents, the MCP servers a public bot would use, and so does the assistant editor's public-bot
    warning. `PersonaView.mcpServers` refs carry `publicBots`.
11. **No new session state for MCP.** Servers resolve at each turn from current settings, so a change takes effect
    on the next question.

## Not in scope

- Lending a person's own token to a group or a public bot (Copilot Studio's maker credentials).
- Members creating their own bots.
- Calling an assistant by name in a message (Dust's `~agent-name`). A candidate for later.
- Approval cards in Zalo, Lark or WhatsApp.
- MCP for Deep research or the Agent Builder, unchanged.

## Risks

- **Prompt injection in group chats:** anyone in a group can ask a tool for whatever the shared token reads.
  - Tool results are already marked as untrusted data.
  - Public bots get read tools only, from servers opened to them.
  - The editor and the dialog say what is exposed.
- **A broad token:** a token that reads the whole Linear workspace exposes all of it.
  - *Chỉ qua trợ lý được chọn* keeps a bot's token to that bot.
  - The runbook advises a narrow, read-only token.
