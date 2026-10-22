# MEM-299: a bot's assistant uses its MCP servers

[MEM-299](https://linear.app/memory-os/issue/MEM-299). The owner agreed to this direction on 2026-10-22.

## Today

- An assistant attaches MCP servers (MEM-273), and on the web a turn opens them through `McpTurnService.open`. The
  person's access applies: the server is organization-wide or granted to one of their Groups. The credential is the
  shared one (`ADMIN`) or the person's own (`PER_USER`). A tool whose policy is `ASK` waits for the person's approval
  card ([chat spec](../../../specs/chat.md)).
- A channel turn opens none of them. `ChatTurnService` resolves the assistant's servers only when
  `command.channel() == null`, because a channel has no Connect card.
- A direct message to a bot that is not public runs as the linked member. A group question, and every turn of a public
  bot (MEM-298), runs as the bot's `SERVICE` account. That account holds only the Basic Group
  ([identity spec](../../../specs/identity.md)).

So a bot cannot answer from Linear or GitHub, even when an administrator has already connected the server with a
shared token and the assistant attaches it.

## How the references do it

Read on 2026-10-22.

- **Onyx** ([MCP docs](https://docs.onyx.app/admins/actions/mcp); source: `tools/tool_constructor.py`,
  `server/features/mcp/credentials.py`, `db/mcp.py`, `onyxbot/slack/handlers/handle_regular_answer.py`):
  - An admin chooses per server between personal keys and one shared key ("All users can share one service account").
    A server with a shared key or no authentication needs no user to connect (`requires_user_authentication`).
  - Access control (organization-wide, or chosen users and groups) decides who may attach the server to an agent
    (`user_can_access_mcp_server`). At run time it is checked only for the default assistant. A custom agent's tools
    run for anyone who can use the agent.
  - The Slack bot runs a channel question as the anonymous user, with every tool of the agent (`allowed_tool_ids=None`).
    OAuth and pass-through tools refuse the anonymous user. Shared-key and unauthenticated tools run.
  - Approval policies apply only in Craft. In chat and agents, an enabled tool runs without asking.
- **Copilot Studio**:
  - A tool can use the maker's credentials instead of each user's. Microsoft names the oversharing risk and lets
    admins block it
    ([maker-provided credentials](https://learn.microsoft.com/en-us/microsoft-copilot-studio/configure-no-maker-authentication)).
  - An agent with *No authentication* is open to anyone with the link, and the security scan warns before it is
    published ([security scan](https://learn.microsoft.com/en-us/microsoft-copilot-studio/security-scan)).
- **Intercom Fin**: data connectors are open to visitors, but a connector that returns a customer's own data needs
  verified identity first, through JWT or a one-time code
  ([FAQ](https://www.intercom.com/help/en/articles/9916507-data-and-actions-faqs)).

## Decisions

1. **The assistant's servers open in a channel, for the turn's actor.** As on the web, every server the assistant
   attaches is resolved for whoever the turn runs as. There is no composer, so each attached server is selected.
   Onyx carries an agent's tools into Slack in the same way.

   | Who asks | Runs as | Servers that open |
   | --- | --- | --- |
   | Linked member, direct message, bot not public | The member | Servers the member may use, with the shared credential or their own. A server they have not connected tells them to connect it in MemoryOS. |
   | Group chat of a bot that is not public | The bot's account | Servers the bot's account may use, with a shared credential or none |
   | Anyone, public bot | The bot's account | As for a group chat, and only servers marked *Cho bot công khai dùng*, read tools only (decisions 4 and 5) |

2. **Access stays the actor's, unlike Onyx.** Onyx stops checking access once a server is on a custom agent. MemoryOS
   keeps checking it at every turn, as on the web. The bot's account holds only the Basic Group, so it may use an
   organization-wide server or one granted to the Basic Group. Who may use a server stays the administrator's
   decision under *Ai dùng được*, not the assistant author's.
3. **No personal credential on the bot's account.** Nobody connects as the bot, so a `PER_USER` server never opens on
   the bot's account. The model is told it is unavailable here. In a member's direct message, the member's own
   credential is used, as on the web.
4. **A public bot uses only servers an administrator opened to it.** A new switch on each server, *Cho bot công khai
   dùng* (`mcp_server.public_bots`, off by default), sits under *Ai dùng được* in the server editor. It is offered
   only for a server with a shared credential or none. Changing the server to personal credentials turns it off.
   Changing it is audited with the server. Onyx has no such step: its Slack channels hold the company's own
   workspace, while a public bot answers strangers on Zalo.
5. **A public bot offers only read tools.** For a public bot, only tools whose risk is `READ` (MEM-264) open, even if
   a write tool is set to `ALWAYS`. The owner agreed that public bots get no write tools.
6. **A channel offers no tool that asks first.** A channel has no approval card, so an `ASK` tool is not offered at
   all. Offering it would only lead to a declined call. Onyx runs enabled tools without asking outside Craft. Here,
   `ASK` is the administrator's explicit choice, and it is kept.
7. **Notices name where to connect, not a button.** `McpTools.unavailableNotice` tells the model a channel turn has
   no button. A server not connected, or whose sign-in expired, is connected in MemoryOS under *Cài đặt › Kết nối*.
   One that is unavailable to the bot's account only an administrator can open. A channel shows no tool steps and no
   MCP App views. Tools only an app calls are already left out of the model's tools.
8. **What a public bot exposes is shown before it is switched on.** The *Công khai* dialog lists, beside each
   assistant's documents, the MCP servers a public bot would use. The assistant editor's public-bot warning names
   them too. `PersonaView.mcpServers` refs carry `publicBots`.
9. **No new session state.** Servers resolve at each turn from current settings, so turning the switch off takes
   effect on the next question.

## Not in scope

- The assistant author's own token (Copilot Studio's maker credentials): the owner chose administrator-held tokens.
- Approval cards in Zalo, Lark or WhatsApp.
- MCP for Deep research or the Agent Builder, unchanged.
- A per-bot spending cap.

## Risks

- **Prompt injection in group chats.** Anyone in a group chat can ask a tool for whatever the shared token reads. Tool
  results are already marked as untrusted data. Public bots get read tools only, and only from servers an
  administrator opened to them. The editor and the dialog say what is exposed.
- **A broad token.** A token that reads the whole Linear workspace exposes all of it. The switch's place in the
  server editor is where the administrator decides. The runbook advises a narrow, read-only token.
