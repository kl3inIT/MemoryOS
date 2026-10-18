# Channel

The Tenant's chat bots on messaging platforms, the Lark bot first. Design and status:
[MEM-246](../increments/active/mem-246-lark-bot/design.md). What a bot receives, how a question becomes a Chat
turn, the Lark adapter, the administrator's API and its page are built and run on staging. This page describes what
exists; operating a bot is in the [runbook](../runbooks/lark-bot.md).

## Connections

A Tenant may have several bots; `channel_connections` holds one row a bot: its provider (`LARK` or `ZALO_BOT`), a
name the administrator gives it, the provider's id of its app (a Zalo bot's is the number before its token's colon),
the provider's public settings of it (`settings`, JSON; a Lark bot's names its region, `LARK` or `FEISHU`, a Zalo
bot's how it receives, `WEBHOOK` or `POLLING`, each of which the database requires), the app's secret as an AES-GCM
envelope, the language of the bot's own notices (`vi` or `en`), whether it is switched on, the service account it
owns, a revision, and what the platform said at the last check: the bot's name and id and the scopes it refused. The
assistant it answers with is Chat's binding ([the bot's assistant](#the-bots-assistant)). One provider app serves one
bot across every Tenant: the provider and the app's id are unique.

`ChannelConnectionService` lists, reads, adds, switches on or off, replaces the secret of, changes the settings of
and removes a bot. Every
call requires `CHANNELS_MANAGE`, an assignable administrative capability that administrators hold; a write takes
the Tenant's exclusive authority lock and checks the revision it was given (`CHANNEL_CHANGED` when it moved on).

- **Checking an app.** Adding a bot, replacing its secret and testing an app ask the platform first, through the
  adapter's `verify(ChannelCredentials)`, given the app's id when the administrator gives one, its secret and the
  provider's settings: they are the provider's shape (`CHANNEL_INVALID` when not), the credential works and the app
  has a bot. The answer names the app's id as the provider normalises it, which is what is stored and checked for
  a second bot of the same app; a replaced secret of another app is `CHANNEL_APP_REJECTED`. The platform is asked between two transactions,
  so no lock and no database connection is held while it answers, and at most two checks run at once (else
  `CHANNEL_PROVIDER_UNAVAILABLE`). A refused credential, or an app without a bot, is `CHANNEL_APP_REJECTED` and
  nothing is stored; a platform that does not answer is `CHANNEL_PROVIDER_UNAVAILABLE`. A scope the bot needs that
  the platform refuses does not stop it: it is recorded and shown. For Lark the check is the connector gateway's:
  a tenant token, `/open-apis/bot/v3/info`, and a probe of `contact:user.email:readonly` and `im:chat:readonly`.
  For Zalo it is `getMe` with the token.
- **Settings.** The adapter's `settings(requested)` checks a bot's settings and completes them with the provider's
  defaults when the bot is added, and when an administrator changes one (`updateSettings`, which moves the
  revision so the process that holds the bot opens it again): a setting the provider does not have, or a value it
  does not take, is `CHANNEL_INVALID`.

- **Adding** validates the name (1 to 100 characters), the app id (a Lark app id starts with `cli_`; a Zalo bot gives
  none, its token is `<number>:<secret>`), the secret (1 to 256 characters) and the language, refuses an app another bot holds (`CHANNEL_APP_IN_USE`), and creates the bot's
  service account ([identity](identity.md#account-classification-and-group-authority)) named after the bot, in the
  same transaction. A new bot is switched off.
- **Removing** deletes the row and retires the service account, so the conversations and costs of the bot keep its
  name.
- The secret is encrypted with `memoryos.channel.credential-encryption-key` and `credential-key-version`, a key of
  the channels' own, apart from the connectors'; the credential kind in the envelope's authenticated data is
  `CHANNEL_<PROVIDER>`. Without the key every bot call fails with `CHANNEL_NOT_CONFIGURED` and nothing else is
  affected. No view carries the secret.
- Each change is audited as `channel.create`, `channel.update` (`change`: `enabled`, `disabled`, `secret`,
  `settings`, `assistant`, `assistant-removed`, and with the group chat's id in `group`: `group-assistant`,
  `group-assistant-removed`, `group-label`, `group-forgotten`) or `channel.delete`.

### The bot's assistant

A bot answers with one assistant and an assistant may have many bots, as LobeHub (`agentBotProvider`), GoClaw
(`ChannelInstance.agent_id`) and OpenClaw (`bindings`) bind a channel to one agent (MEM-273). The binding is Chat's
(`chat_channel_binding`, through `ChatChannelBindings`), because Chat decides for each turn whether a conversation held
in a channel may still use the bot's assistant. A bot without a row answers with the Tenant's built-in assistant;
removing the assistant row removes the binding, removing the bot removes it too, and a deleted assistant counts as
none.

- **Binding** (`bindPersona`) takes `CHANNELS_MANAGE` and edit rights on the assistant the bot goes to and on the one
  it leaves while that one exists (else `CHANNEL_ASSISTANT_NOT_EDITABLE`; an assistant the caller cannot use is
  `CHAT_UNAVAILABLE`), since binding decides who may ask an assistant. A bot bound to another assistant moves in the
  same step; choosing the built-in assistant unbinds. Binding keeps the revision and does not reopen the bot: the
  assistant is read again for each question.
- **Reading** (`boundTo`) lists where an assistant answers to anyone who may use it, without app, secret or account:
  each bot it is the default of (`byDefault`) or whose group chats are given it, with those groups.
- **Answering.** A new conversation is created with `ChatSessionService.createInChannel(actor, title, bot, group)`,
  which records the bot and, in a group, the group chat on it (`chat_session.channel_chat_id`) and holds it with the
  assistant they resolve to (`ChatSessionService.channelPersona`): the group's own live assistant, else the bot's live
  custom one, else the built-in one. A question asked through the bot runs with that assistant while the bot and the
  group still resolve to it, whether or not the sender may use the assistant elsewhere, as Onyx's Slack bot answers
  with the persona of the channel's config; moving or unbinding the bot, or giving its group another assistant or
  none, ends that at once, and the same conversation continued from the web needs the owner to be able to use the
  assistant (`CHAT_UNAVAILABLE`). The documents a turn reads stay the sender's own: Sources, Document Sets and search
  are resolved for the sender, or for the bot's account in a group, and the assistant's files are read only by
  someone who may use the assistant. A conversation held with another assistant than its bot and group resolve to now
  is not continued, so moving a bot or changing a group's assistant starts its conversations over. A bot switched off
  receives nothing, so its binding admits no channel turn.

### A group chat's own assistant

A bot keeps its assistant as its default, and an administrator may give each group chat the bot is in an assistant of
its own, as Onyx configures a Slack bot by channel under one default config (`SlackChannelConfig`, `is_default`) and
OpenClaw binds one group to an agent (MEM-273, [design](../increments/active/mem-273-agent-editor/design-groups.md)).
Direct messages always answer with the default.

- **The groups** are those the bot has answered in (`channel_groups`, the channel's): `ChannelInboundService` records
  a group when it accepts a group's addressed question that is not external, with the name the provider gives
  (`ChannelAdapter.chat`: Lark's from the `im/v1/chat get` the external check already makes, cached an hour; Zalo
  gives none), and moves its last message on each one. A Lark bot's groups are also known before anyone asks there
  (`ChannelGroupRoster`, MEM-273 5d): the adapter passes the `im.chat.member.bot.added_v1` event as
  `ChannelReceiver.joined`, recorded with no last message, and `ChannelAdapter.groups` reads Lark's whole list of the
  bot's groups (`im/v1/chats`, without outsiders' or disbanded groups) when the bot connects and every 10 minutes
  (`ChannelSupervisor.LISTING`) on a thread of its own; a group missing from it is removed with its own assistant. A
  list read in part changes nothing; Zalo lists none. An administrator may label a group (up to 100 characters, a
  blank one clears it), shown before the provider's name. A Zalo group stays until it is forgotten, which removes its
  assistant too; the page offers no forgetting of a Lark group, which leaves with the bot. Removing the bot removes
  its groups.
- **The group's assistant** is Chat's (`chat_channel_group_binding`, through `ChatChannelBindings`), for the reason the
  default is: one row a group, removed with its assistant's row, with the bot and when the group is forgotten. A
  deleted assistant counts as none, so the group answers with the bot's default.
- **Setting it** (`bindGroupPersona`) takes `CHANNELS_MANAGE`, a group the bot has answered in (else
  `CHANNEL_GROUP_NOT_FOUND`), and edit rights on the assistant the group goes to and on the one it leaves while that
  one exists (`CHANNEL_ASSISTANT_NOT_EDITABLE`); the built-in assistant is everyone's and takes none, and may be a
  group's own assistant in place of a custom default. `null` returns the group to the default. Labelling
  (`labelGroup`) takes `CHANNELS_MANAGE`; forgetting (`forgetGroup`) takes it and, as it removes the group's
  assistant, the edit rights leaving it takes. The bot and its groups check the assistant they leave in one place.

### The administrator's API

`/api/channels` (`ChannelController`, tag `Channels`) exposes the service; each mutation needs the CSRF header and
each change of a bot an `If-Match` of its quoted revision.

| Operation | What it does |
| --- | --- |
| `GET /api/channels` | The Tenant's bots, each with its status and the state its process last wrote |
| `POST /api/channels/lark/test` | Checks a Lark app (`region`, `appId`, `appSecret`) and stores nothing |
| `POST /api/channels/lark` | Checks the app and adds its bot, switched off; `201` |
| `POST /api/channels/zalo/test` | Checks a Zalo bot's `token` and stores nothing |
| `POST /api/channels/zalo` | Checks the token and adds the bot (`name`, `token`, `noticeLanguage`, optional `transport`), switched off; `201` |
| `PUT /api/channels/{id}/transport` | Changes how a Zalo bot receives (`WEBHOOK` or `POLLING`) |
| `PUT /api/channels/{id}/secret` | Checks the new secret and replaces the old one |
| `PUT /api/channels/{id}/enabled` | Switches the bot on or off |
| `PUT /api/channels/{id}/persona` | Binds the bot to an assistant (`personaId`), moving it from another, or to none (`null`); no `If-Match` |
| `GET /api/channels/{id}/groups` | The bot's group chats, the most recently asked first and those nobody has asked in last: `id`, `label`, `name`, `firstMessageAt`, `lastMessageAt` (absent before a question), `assistant` |
| `PUT /api/channels/{id}/groups/{groupId}/persona` | Gives a group chat its own assistant (`personaId`) or returns it to the bot's default (`null`) |
| `PUT /api/channels/{id}/groups/{groupId}/label` | Labels a group chat (`label`); blank or null clears it |
| `DELETE /api/channels/{id}/groups/{groupId}` | Forgets a group chat and its assistant; `204` |
| `DELETE /api/channels/{id}` | Removes the bot and retires its account; `204` |
| `GET /api/chat/personas/{id}/channels` | Where an assistant answers (`id`, `provider`, `name`, `botName`, `enabled`, `byDefault`, `groups`), for anyone who may use it |

A bot's `status` is `OFF` when switched off; `NOT_RUNNING` when its supervisor has not written for three passes
(90 seconds), so no process holds it; else `CONNECTED` while its connection is open, `FAILED` after a failure to open
it, with the failure's typed code and time, or `CONNECTING`. A bot carries `region` for Lark and `transport` for
Zalo, each absent for the other, and `assistant` (`id`, `name`) when it answers with a custom assistant. No response
carries the secret or the token, and the request records redact them.

### The administration page

`/admin/channels` (`web/src/features/channels`, "Kênh chat"), shown to a holder of `CHANNELS_MANAGE` under the agents and tools
section, lists the bots as the MCP servers are listed: the platform's mark (Lark or Zalo), the bot's name, one line
and the switch, which saves on change. The line names what is wrong first (no connection, with Zalo's own reason for
a webhook Zalo cannot reach or a deployment without a public address; the bots' process not running), then a scope
Lark refuses, else that the bot is off, connecting or answering; the list is read again every five seconds while a
bot switched on is not connected. Each bot has its page, `/admin/channels/{id}`: its status, the platform's name for
the bot, a Lark bot's region and App ID or a Zalo bot's Bot ID and way of receiving (a select of Webhook or Polling
that saves on change through `PUT /api/channels/{id}/transport`), notice language, when it connected, its last
message and its last failure, the scopes still missing, and a menu to replace the App Secret or the token, or delete
the bot. Under *Trợ lý* the page sets which assistant answers where, as Onyx's Slack bot sets one per channel: *Trợ lý
mặc định* picks the bot's default (the built-in assistant first, then the assistants the administrator may edit,
through `PUT /api/channels/{id}/persona`), and *Theo nhóm chat* lists the group chats the bot has answered in, each
with the platform's mark, its label or name (a Zalo group without a label is *Nhóm Zalo* and the end of its id), *Nhóm
Lark* or *Nhóm Zalo* and when it last asked, an assistant picker whose first choice is *Theo mặc định*, and a menu to
rename it (*Đổi tên nhóm chat*, blank clears the label) or forget it after a confirmation. "Nhóm chat" keeps these
apart from MemoryOS's own Groups of people. Each choice saves on change; a refusal such as
`CHANNEL_ASSISTANT_NOT_EDITABLE` shows above the section. With no group yet, the list is only its heading. Each row of
the list ends its line with the bot's assistant. Adding a bot is a dialog that asks the platform first, then shows that platform's form beside its setup
steps: Lark's App ID and App Secret with the console steps and scopes, or Zalo's token and way of receiving with the
Zalo Bot Creator steps. A secret or token stays in a ref, never in query state or storage, and is cleared once sent.

Settings › Chat shows "Liên kết Zalo" to a member when the Tenant has a Zalo bot: one row a bot, linked (with when)
or not. "Lấy mã" makes a code and shows it large and copyable with the time it has left; while it is shown the links
are read every three seconds, and the dialog closes once the bot took the code. "Bỏ liên kết" removes a link after a
confirmation. The route composes the section into the chat settings page, since `chat` does not import `channels`.

## The adapter

A platform is a `ChannelAdapter`, one a `ChannelProvider`, found through `ChannelAdapters`; the port carries no
provider's shape. It owns the transport, the provider's ids and the shape of a reply, and states what the
provider's chats can do with `features()`: `ChannelFeatures(organisations, editsReply, maxText)`, whether the
provider names an organisation for each sender, whether a reply can be rewritten while it is written, and the
longest text one message takes. `reply(connection, message)` opens a `ChannelReply` that quotes the message where
the provider can: `append` text as it is written, called only when the reply can be rewritten; then
`finish(pieces, sources, footer)`, given the whole answer as one piece when the reply can be rewritten and otherwise
cut by `ChannelText.pieces` at `maxText` (at the last paragraph, line, sentence or space that fits), returning the
provider's id of the answer's last message, or throwing `ChannelReply.Incomplete` with the last id sent when some
of its messages reached the chat and a later one did not (the sender is told the rest is missing, `INCOMPLETE`, and
the message settles `FAILED`/`ANSWER_INCOMPLETE` with that id); or `fail(notice)`. Whatever fails once the reply is
open ends it with `fail`. `notice` sends one short message; `sender` answers
a `ChannelSender` with the e-mail the provider holds, if any; `externalChat` says whether people outside the
organisation read a chat, false for a provider without organisations. It hands over a `ChannelMessage`: the
provider's message id, the chat and whether it is `DIRECT` or `GROUP`, the sender, the sender's organisation and
the bot's as the provider names them (none for a provider without organisations), whether the bot was named,
`TEXT` or `OTHER`, the text without the bot's mention, and the message it replies to.
`open(connection, secret, receiver)` starts receiving for one bot and returns a `ChannelTransport` (`connected()`,
`close()`), which either keeps a connection of its own or names the `ChannelWebhook` the provider pushes to:
`authorized(headers)` checks the provider's secret from the headers alone, `accept(headers, body)` reads the messages
of an authorized request, and
`register()` gives the provider the webhook's address (`ChannelWebhook.path`, `/hooks/channels/<connection id>`
under the public address) once it is served, failing with `CHANNEL_WEBHOOK_UNREACHABLE` when the provider's check
of the address did not arrive. The adapter acknowledges each message to the provider and hands it to the receiver,
which returns at once. `settings(requested)` checks a bot's settings. Lark's transport keeps a connection and Lark
states organisations, a reply it rewrites and 150,000 characters; Zalo's is below.

## The Zalo adapter

`ZaloChannelAdapter`, in `memoryos-integration-zalo` ([MEM-263](../increments/active/mem-263-zalo-bot/design.md)),
calls the Zalo Bot Platform (`memoryos.zalo.base-url`, `https://bot-api.zaloplatforms.com/bot<token>/<method>`)
through an `@HttpExchange` client on `shared.OutboundHttp`, since no SDK is maintained: 15 seconds a call, 45 for a
poll, 5 for the typing mark, 1 MiB. A failure is known by Zalo's `error_code`, else the status; its text, the
address that holds the token and the cause are never kept. Zalo states no organisations, no reply it rewrites and
2,000 characters. What a real bot showed is recorded in the
[increment's design](../increments/active/mem-263-zalo-bot/design.md#what-a-real-bot-showed-2026-10-15).

- **A bot** is its token from Zalo Bot Creator; `getMe` names it by its display name, which members type after @,
  else its account name. Its one setting is how it receives: `WEBHOOK` where the deployment has a public HTTPS
  address (`memoryos.channel.web-url`), which is then the default, else `POLLING`; `WEBHOOK` without such an address
  is `CHANNEL_WEB_URL_MISSING`. Each opening reads the display name again, since it can be renamed in Bot Creator.
- **Webhook.** Each opening makes a secret of 32 random bytes kept in memory; `register()` calls `setWebhook` with
  the bot's address and that secret and reads Zalo's check of the address from its answer (`testWebhook` when the
  answer carries none; a day's checks spent, `426`, on that check is not held against the bot, but a `426` from
  `setWebhook` itself leaves no address set and fails the opening); a failed check is `CHANNEL_WEBHOOK_UNREACHABLE`
  with Zalo's outcome (`webhook.err.dns` and the like) in its diagnostic. A request is Zalo's when its
  `X-Bot-Api-Secret-Token` equals the secret, compared in constant time from the headers before any body is read; a
  body of Zalo's that is no message is accepted and carries none. The transport asks `getWebhookInfo` at most once a
  minute whether the address is still this one (`404` is none), and `close()` calls `deleteWebhook`.
- **Polling.** Opening calls `deleteWebhook` (`400` when none is set is no failure), since Zalo serves no poll while
  a webhook is set, then one virtual thread polls `getUpdates` (30-second wait; one update an answer, as an object;
  `408` is an empty poll; an empty answer sooner than a second waits a second; errors back off from one second to a
  minute); a refused token stops it, and the supervisor opens it again.
- **A message** is `{event_name, message}`: `message.text.received` is `TEXT`; `message.image.received` with a
  caption is `TEXT`, the caption followed by a note that the image was not read; any other event `OTHER`; a sender
  Zalo marks as a bot is dropped, and a sender without `display_name` is known by `name`. A `PRIVATE` chat is
  direct. In a `GROUP` (Zalo's beta) Zalo pushes only messages that name the bot, so every group message addresses
  it; the mention is plain text, `@<display name>`, removed where it is found whole (ignoring case, on NFC text, the
  name ending at a space, punctuation or the end). No sender e-mail, no external chat.
- **The reply** sends Zalo's typing mark at once and every four seconds, off the caller's thread, until it ends and
  for 60 seconds at most. `finish` joins the pieces with the sources (a numbered list of titles and bare addresses)
  and the closing line, makes them text Zalo shows (`ZaloText`: a link or image its text and address, code its text,
  a heading a bold line, a table one `header: value` line a cell, a quote its text; bold, italic and lists kept, since
  Zalo renders them), cuts the whole at 2,000 characters, puts the asker's name before the first in a group (Zalo has
  no quote) and sends each with `parse_mode: markdown`; markdown Zalo refuses is sent again as plain text, and a
  message that fails after others were sent is `ChannelReply.Incomplete`. `fail` and a notice are plain messages;
  `acknowledge` is the typing mark. Zalo's `429` is `CHANNEL_PROVIDER_QUOTA`, the bot's plan spent.

## The Lark adapter

`LarkChannelAdapter`, in `memoryos-integration-lark`, runs the bot on the SDK's `LarkChannel` (oapi-sdk 2.8.5): the
long connection to the app's region (`open.larksuite.com` or `open.feishu.cn`), mention required in a group and
`@all` ignored, the SDK's chat queue off (it orders and merges by chat; the capability orders by conversation), its
in-memory deduplication on and not relied on, and the raw event kept so that the organisations are read from it: a
custom app belongs to one organisation, which every event's header names, and the sender's is in its body. Requests
go through a `LarkTransport` of its own with a 30-second, 1 MiB budget for the calls the SDK makes itself.

- A message's text is the `text` or `post` content without the bot's own mention; another person's mention stays,
  by name rather than by Lark's `@_user_N` key. Any other message type is `OTHER`. A reply names the message it
  answers, or the topic's root.
- An answer is a card of the bot's own (`LarkReplyCard`), not the SDK's Markdown stream, whose card has a fixed
  English header and placeholders. The card (schema 2.0, no header, streaming on) is created through CardKit,
  showing the bot's `WRITING` notice in its language, and replies to the question by its id (`replyInThread` false,
  with a `uuid` named after the question so a retried send is not doubled); a card or reply Lark refuses fails the
  message before the turn starts. One writer rewrites the card's Markdown element with the whole text at most every
  800 ms, each write with a higher sequence; a write Lark refuses because the card's streaming ended (300309)
  reopens it once. The answer ends as its text, its sources as a numbered list of links and the closing line, cut
  at 9,000 characters; the card's streaming is closed with the answer's first line as its chat-list summary. A
  failure keeps what was written and adds the notice below it. A notice is a text message quoting the question.
- Text the model wrote, and a source's title, carry Lark's `<at>` and `<person>` tags written out, so an answer or a
  document it quotes cannot notify a person or the whole group.
- The sender's e-mail is the contact `email` of their `open_id` (scope `contact:user.email:readonly`); none when
  Lark will not say. Lark keeps it unique within an organisation and its administrator assigns it; whether members
  may edit it is a field setting of the organisation's admin console
  ([design](../increments/active/mem-246-lark-bot/design.md#receiving-a-message)).
- A group is external unless Lark answers that it is not; the answer is remembered for an hour, and a chat whose
  answer cannot be read counts as external.

## Holding connections

`ChannelSupervisor` holds open the transport of every bot that is switched on, in the process that receives
(`memoryos-channels`); it is not a component, so no other process opens a connection. Every 30 seconds it opens a
bot switched on since, reopens one whose revision changed or whose transport reports it is no longer connected,
closes one switched off or removed, and writes `channel_connection_state` (since when it is open, the typed code
and time of the last failure, the last message, a heartbeat). A failed open, an unreadable secret among them, is
recorded and tried again at the next pass, except a webhook the provider could not reach and a deployment without a
public address (`CHANNEL_WEBHOOK_UNREACHABLE`, `CHANNEL_WEB_URL_MISSING`), which wait for the bot's next revision
rather than spend the provider's daily checks. The secret is decrypted for the open only and the buffer cleared.
A transport with a webhook is served before its `register()` runs, so the provider's check reaches it;
`authorize(id, headers)` asks that webhook about the headers before the route reads a body (`UNKNOWN` when no bot
here receives over HTTP, `REFUSED` when it is not the provider's), and `deliver(id, headers, body)` hands the
request to it and claims each message before it returns, so the provider is answered only once its messages are
recorded. A message a transport hands over itself, as a poll, is claimed on the transport's thread the same way,
since a provider that polls keeps nothing once it has handed a message over. Each message is answered on a virtual
thread of its own. Every pass also settles as `FAILED`/`INTERRUPTED` the messages claimed before this process began
and at least ten minutes ago that were never settled, which only a process that ended while handling them leaves.
Stopping closes every connection, then waits up to 30 seconds for the answers in progress.

## The process

`memoryos-channels` runs the supervisor and the turns. Its application scans only its own package and imports
`ChannelRuntimeComponents`, which scans what a turn reaches (`channel`, `chat`, `ai`, `retrieval`, `connector`,
`document`, `library`, `objectstorage`, `mcp`, `usage`, `audit`, `iam`) and leaves out what serves a browser or
another process: Keycloak administration, invitations, identity providers and sign-in, the MemoryOS MCP endpoint
and its trusted apps, the library page's shelves; ingestion, meetings and voice are not scanned. Every external
Source type's adapter is on its classpath, because a turn's search reaches them, and Zalo's. It runs no migration
(the API does and starts first; this process validates the schema) and serves, on port 8082, `/actuator/health`
(readiness: the database) and `POST /hooks/channels/{id}` (`ChannelWebhookController`, no session): a body over
64 KB is `413`, else `200`, `403` or `404` as `deliver` answers. The web's nginx sends `/hooks/channels/` to
`memoryos-channels:8082` with a 64 KB body; where that process does not run, the name does not resolve. It runs at most 24 turns with no separate bound for one person, since every group turn is the
bot's account. Its database pool is 5. It runs on staging only ([runbook](../runbooks/lark-bot.md)).

## Receiving a message

`ChannelInboundService.receive` handles one message to its end, on the adapter's thread, after the adapter has
acknowledged it to the provider:

1. The bot must be switched on. A group message that does not name the bot is dropped unrecorded.
2. The message is claimed by inserting `channel_messages (connection_id, external_message_id)`; a message handed
   over again, by this or another process, ends here. The row holds ids only (the chat, the sender, then the
   session, the answer and the provider's id of the answer) and how it ended (`ANSWERED`, `REFUSED`, `FAILED`
   with a reason); the text is in the chat session.
3. It is refused with a notice when the sender's organisation is not the organisation the event names for the
   bot's app, or not the one recorded for the bot when one is (`OUTSIDER`; an event that names neither admits
   nobody), when it is not text (`NOT_TEXT`) or when the group has people outside the organisation
   (`EXTERNAL_GROUP`). A group message that only names the bot, empty once the mention is removed, is refused without
   a notice (`EMPTY`). A provider without organisations admits only a linked sender instead
   ([linked accounts](#linked-accounts)).
4. The sender's account is the one they linked on this bot; else the one active person whose verified e-mail
   equals the e-mail the provider holds ([identity](identity.md#account-classification-and-group-authority)),
   remembered in `channel_identities` for `memoryos.channel.identity-ttl` (1 hour); a sender without one is
   remembered as such.
5. A direct message from a sender with an account runs as that person. A group message, and a direct message from
   a sender without an account, runs as the bot's service account, which reads what every member may read.
6. The conversation: a reply to a message, the question or the bot's answer, continues that message's session
   however old and whoever replies, looked up in the same chat, so a reply in a group never reaches a direct
   conversation; a new direct message continues the one in `channel_conversations` while its last message is
   younger than `memoryos.channel.conversation-idle` (1 hour). A group has one conversation, on every provider, as
   OpenClaw keys a session by group (its Feishu default `groupSessionScope: group`): kept in
   `channel_conversations` for the group (`external_user_id` `@group`) and continued by every member's question
   while its last message is younger than `memoryos.channel.group-conversation-idle` (4 hours). `/new` forgets the
   direct or the group conversation and says so. A group's conversation runs as the bot's account, so it holds only
   what everyone in the group may read, and each question in it carries its asker's name. A session is continued only by the account that
   owns it and is held with the assistant the bot answers with now in that chat, so a sender who gained or lost an
   account starts over, and so does every conversation of a bot moved to another assistant and every conversation of
   a group given another one. A group the bot answers in is recorded in `channel_groups` before the turn.
7. The question becomes a Chat turn through the API the web uses: a session created with
   `ChatSessionService.createInChannel` and the assistant the bot is bound to when the question arrives, a `SEND` at `ChatSessionService.leaf` with a request id derived from
   the provider's message id, the reply's stream read for text, and the saved answer read for its text and
   sources. A direct answer is finished with a closing line: the link that opens a sender's own conversation in
   MemoryOS, or, for a sender without an account, "sign in". A group answer has none: its conversation continues
   by itself, and no reference bot adds one.

A failure is a notice and a recorded reason, never a provider message or the question: a refused or busy turn
says "busy", a spend limit names the limit, anything else says the bot could not answer. Notices are in the
connection's language.

A claimed message is one `memoryos.channel.message` observation (`provider`, `kind`, `outcome`: `ANSWERED`,
`REFUSED`, `LINKED`, `NEW_CONVERSATION` or `FAILED`), with a child `memoryos.channel.message.step` for each step (`step`:
`link`, `refusal`, `sender`, `conversation`, `reply`, `turn`, `finish`); the Chat turn's spans sit under `turn`, so a trace
shows where the time before an answer goes.

### Linked accounts

A provider whose sender has no e-mail and no organisation, such as Zalo, answers only members who linked their
account on it to their MemoryOS account ([MEM-263](../increments/active/mem-263-zalo-bot/design.md)).
`ChannelLinkService` keeps the links (`channel_account_links`) and the codes that make them (`channel_link_codes`).

- **The code.** A member makes it in MemoryOS (`POST /api/channel-links`): eight characters of
  `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, valid ten minutes, stored as its SHA-256 hash, one live code a member and
  bot, which a new one replaces. The member sends it to the bot in a direct message; the account that sends a live
  code is linked to the member who made it, the code is used up, and the bot says so (`LINKED`). A code in a group
  is not read. A message is a code only when it is one code alone, case and hyphens aside.
- **The link.** One account on the platform links to one member and a member to one account a bot, which the
  database holds both ways. A member who links another account of theirs replaces their old link; an account linked
  to another active member is never moved by a code (`LINK_ALREADY`; the code is put back and stays its member's):
  that member unlinks it first, so an account cannot change hands without its member knowing. A linked sender's code
  is not tried at all, so a live code and a wrong one get the same answer. It does not expire; it is removed by the member (`DELETE /api/channel-links/{connectionId}`,
  which also drops their code), with their membership and with the bot, and it is ignored while the member is not
  active. `GET /api/channel-links` lists the Tenant's bots that take a link with the member's own on each.
- **Audit.** A link is audited as `channel.account_link` and a member's unlinking as `channel.account_unlink`
  (`ACCOUNT_CHANGE`), each with the member as the actor and the bot (`CHANNEL`, its id and name) as the resource,
  and nothing of the platform account, the code or a message. A link removed with the membership or the bot is not
  audited apart from that removal.
- **Who is answered.** A linked sender is answered as any member is: as themself in a direct message, as the bot in
  a group. A sender who is not linked is told how to link (`LINK_REQUIRED`) in a direct message and, in a group,
  only to message the bot directly (`LINK_IN_DIRECT`), so the instructions are not posted for everyone; at most once
  a minute in each chat, so a group's notice never silences the instructions in the sender's direct chat (later
  messages settle `LINK_REQUIRED_QUIET` without a notice); and is not answered, in a group too. When
  `memoryos.channel.web-url` is set, the instructions end with the bare address of Settings › Chat, where the code
  is got; the bot's page offers "Liên kết tài khoản của bạn", which opens it. A provider without
  organisations knows a sender only by the link: no e-mail and no remembered identity is read, and a link removed
  while a message is handled leaves it unanswered rather than answered as the bot.
- **Guessing.** Every code an unlinked sender sends in a direct message is an attempt: it is recorded on its message
  as `LINK_CODE_INVALID` before it is tried and stays so unless it links, so attempts still in flight count. A
  sender with five within the hour is not heard for the rest of it (`LINK_LOCKED`, no notice), questions included.
  A code that does not link gets one notice, whether it is wrong, used up, expired or another bot's; no notice, log
  or record names the member a code or a link belongs to, and no log carries a code or a message's text.
  The sender's rate counts every message, codes included, and is checked before a code is tried.

The Worker task `memoryos-channel-message-retention-v1` runs daily and deletes `channel_messages` rows older than
`memoryos.channel.message-retention` (90 days); a reply to a message that old starts a new conversation.

- **A sender's rate.** A person who sent the bot more than `memoryos.channel.sender-rate` messages (10) in the last
  minute, this one included, is refused (`RATE_LIMITED`) and told to wait a minute.
- **One turn a conversation.** A message whose conversation already runs a turn waits for it, asking again every
  second at the conversation's new leaf, up to `memoryos.channel.order-wait` (5 minutes); then it is refused as
  busy. While it waits it carries the `Get` reaction, put once, so the sender sees it was taken. The reply card is
  opened only once the question is accepted, so it is not shown while the question waits, and it is written while
  the turn is being prepared.
- **The asker's name.** A turn that runs as the bot is asked `[name]: question`, the name the provider shows for the
  sender cut to a plain label of at most 64 characters, so the answer addresses the right person. A turn that runs
  as the sender is asked the question alone. The session's title is the question either way.
- **Where the turn is asked.** A channel turn's system prompt ends with a `# Conversation` section, after the
  agent's instructions and before the member's information, naming the platform (`Lark`, `Zalo`): in a group, that
  each message starts with `[name]:`, the sender's name and not part of the question, and that only this opening
  label is MemoryOS's, so a name typed elsewhere is the sender's words; that the answer goes to that
  person and is posted to the group, short and without tables; in a direct message, only where it is (OpenClaw
  `buildGroupChatContext`). A turn continued on the web gets none.

- **The way to the web.** A direct answer that ran as the sender ends with a link, "Continue in MemoryOS", to the
  same session at `memoryos.channel.web-url` (`MEMORYOS_CHANNEL_WEB_URL`) + `/chat/{session}`, which opens and continues
  there although it is not in the sidebar; with no address configured there is no link. A group's session is the
  bot's, so a group answer has none.
