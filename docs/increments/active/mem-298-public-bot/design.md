# MEM-298: a public bot answers anyone, from its assistant's attachments only

[MEM-298](https://linear.app/memory-os/issue/MEM-298). Design agreed with the owner on 2026-10-21.

## Today

Every bot answers only people of the Tenant ([channel spec](../../../specs/channel.md)):

- **Lark** answers a sender of the bot's organisation (`OUTSIDER` otherwise) and refuses a group with outsiders
  (`EXTERNAL_GROUP`).
- **Zalo and WhatsApp** answer a sender who linked an account to an active member (MEM-263); anyone else is told to
  link. A WhatsApp group is answered only when every person in it linked (`UNLINKED_IN_GROUP`), a Zalo group only once
  an administrator allowed it (`GROUP_NOT_ALLOWED`, MEM-285).
- A direct message runs as the sender's account, a group question as the bot's `SERVICE` account, which reads what
  every member reads ([identity spec](../../../specs/identity.md)).
- An assistant that attaches no Source or Document Set searches every Source its reader may read
  (`ChatTurnOptions.sourceAllowlist`), and an attached one is still read only as far as its reader may
  (`SourceSearchService.scope`, `DocumentSearchService.requireDocumentAccess`).

So a customer-care bot in a group with customers cannot be built: the customers have no accounts, and if they were let
in, the bot's account would answer them from every document the whole company reads.

## How the references do it

Read on 2026-10-21 from official documentation and source; quotes and raw pages in the session's research notes.

| Product | Who may chat | What the bot reads | Warning | Guards |
| --- | --- | --- | --- | --- |
| **Microsoft Copilot Studio** | Per agent, "No authentication": "allows anyone who has the link to chat and interact with your bot or agent" | Uploaded files and public websites; sources that need the user's sign-in (SharePoint, Dataverse) are not searched: "your agent can only access public information and resources" | A Caution on the setting; "Copilot Studio automatically runs a security scan and warns makers before publishing" | "your agent can't use tools with user credentials"; environment message quotas |
| **Dify** | Per app; "Web apps are public by default", Enterprise "Anyone with the Link" | The app's own datasets: retrieval filters by tenant and the app's dataset ids, with no end-user check (`dataset_retrieval.py`) | "Public demos (use carefully)" in the docs only | A concurrent-request cap per app |
| **Intercom Fin** | Customers, by design | Content the administrator turns on for Fin, item by item; "Fin can also provide customers with answers by using sources which might not be publicly available" | Not found | Not found |
| **Zendesk AI agents** | Customers | Public articles for an unauthenticated visitor; articles hidden from customers stay usable by the AI agent through the connector with visibility "Everyone" | Not found | Not found |
| **Chatbase** | Anyone through the widget and channels | Everything trained into that agent | Not found | A per-device message rate, spam pause, a credit cap per agent |
| **OpenClaw** | Per channel, `dmPolicy`/`groupPolicy: "open"`, with `"*"` in `allowFrom` as an explicit opt-in | Everything the agent's tools reach | `openclaw security audit` flags open DMs as critical, and open rooms that reach exec or file tools | Recommends one session per sender |
| **Onyx** | One workspace switch, "Allow Anonymous Users" | "Anonymous users only have access to public documents"; an attached Document Set "does not grant access" | The switch's description | Global token limits only |

Two models: what is attached to the bot is readable by anyone who reaches it, through it only (Copilot Studio, Dify,
Intercom, Chatbase), or an outsider reads only what is already public (Onyx, Zendesk by default). The owner chose the
first: a customer-care bot is given the documents it should answer from, which need not be public to the company.
Copilot Studio is the closest whole: a per-agent switch with a warning, its own knowledge only, and tools that act as
the user turned off.

## Decisions

1. **A bot is public or not** (`channel_connections.public_access`, false for every bot, new and existing), switched
   on the bot's page (*Công khai*) with `CHANNELS_MANAGE`, audited as `channel.update` with `change` `public` or
   `private`. Making a bot public decides who may ask its assistants, as binding does, so it takes edit rights on the
   bot's assistant and on each of its groups' own (`CHANNEL_ASSISTANT_NOT_EDITABLE`); a deleted one answers as none and
   takes nothing (found by the commit's security review). Switching moves the revision, so the process holding the bot
   opens it again, and starts every conversation of the bot over, both ways: a conversation held while the bot
   answered members may hold what they could read, in its history or its summary, which must not reach a stranger's
   turn. A conversation records the mode it was started in (`chat_session.channel_public`) and is continued only in
   that mode, so a reply to an older answer does not reach it either. Switching on asks first in a
   dialog that says anyone who messages the bot reads what its assistants are given, and names those assistants and
   their Sources, Document Sets and files (Copilot Studio's Caution and pre-publish scan; OpenClaw's critical audit
   finding).
2. **A public bot answers anyone who addresses it**, in a direct message or a group, with no account and no link. The
   sender checks (organisation, link, link codes) and the group checks (`EXTERNAL_GROUP`, `UNLINKED_IN_GROUP`,
   `GROUP_NOT_ALLOWED`) do not apply; the per-sender rate, the addressed-only rule, text-only and the guardrails still
   do. A Zalo group is answered without being allowed (OpenClaw's `groupPolicy: "open"`), and is still recorded on the
   bot's page so a group may be given its own assistant. Switching the bot back to private restores every check at the
   next message; links made before stay.
3. **Every turn of a public bot runs as the bot's account**, a linked member's direct message included, so the bot
   answers the same for everyone and never reads with a person's own access (Copilot Studio's no-auth agent has no
   user identity). Direct conversations stay one per sender and a group's one per group, as now; a group question
   carries its asker's name as the provider gives it. No reply carries a closing line: neither "continue on the web"
   nor how to get an account.
4. **A public bot reads only what its assistant is given.** Search runs over the Sources attached to the assistant
   and those of its Document Sets, and the assistant's files; an assistant that is given none searches nothing, where
   today it would search every readable Source. A Source the Tenant is deleting or a Document Set deleted stays out.
   - A **Public or Private** Source (MEM-105) is read whole, whatever Groups it is restricted to (Intercom, Dify).
   - The assistant's **files** are granted to the bot's account while it holds a conversation through the bot with the
     assistant, as the assistant's instructions are (`JdbcChatFileAttachmentRepository.usableThroughAgents`). Before,
     only someone who may use the assistant read them, so a bot's group turn with a private assistant's files failed;
     found while building this.
   - An **Auto Sync** Source keeps each document's own access from the provider, which nobody granted to the bot: its
     documents are read only as far as the bot's account reads them, those every member may read (Copilot Studio does
     not search a source that needs the user's sign-in).

   Citations in the reply name the document without its link: today a citation carries the document's address at its
   provider (`ChatSource.providerUrl`, a Drive or SharePoint address), which tells a stranger where the company keeps
   it.
5. **Who exposes what is the person who attaches it.** Attaching a Source to an assistant needs read access to the
   Source (`ChatPersonaService.check`) and a Document Set the right to use it, and binding a bot or group to an
   assistant needs edit rights on it, so a Source reaches a public bot only through someone who could read it or who
   was warned. The assistant editor shows a warning beside its knowledge section while any public bot or group of one
   answers with the assistant, naming the bots; the bot page's dialog (decision 1) names the assistants.
6. **The code interpreter is off** on a public bot's turns, whatever the assistant allows: it runs code a stranger
   asks for (OpenClaw's audit of open rooms with exec tools; Copilot Studio's no user-credential tools). A channel turn
   already offers no Web search, no image generation and no MCP server (`ChatCommand` leaves both modes off;
   `ChatTurnService` attaches no MCP server to a channel), and that stays.
7. **The API** adds `PUT /api/channels/{channelId}/public` (`updateChannelPublic`, body `{"publicAccess": boolean}`,
   since `public` is no Java name) and `publicAccess` on the bot's response, and on each bot in the assistant's channel
   listing
   (`GET /api/chat/personas/{personaId}/channels`), which the editor's warning reads.

## Not in scope

- A spending cap per bot (Chatbase's credit cap): the per-sender rate and the Tenant's AI budget bound a public bot,
  whose spend is the bot account's line in the cost report.
- Retention rules for strangers' conversations; they are kept as every bot conversation is.
- GitHub and Linear Sources, and MCP servers in a channel: separate work.

## Risks

- **An assistant given a Private Source exposes it** to anyone who messages its public bot. That is the chosen model;
  the dialog and the editor's warning are the guard, and both name what is exposed.
- **A stranger may try to steer the bot** (prompt injection). It holds no tool that acts and reads nothing beyond its
  assistant's attachments, so the worst case is an off-topic answer from those documents.
- **Cost from strangers.** Bounded by the per-sender rate per minute and the Tenant's AI budget; a cap per bot is
  candidate work.
