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
owns, a revision, and what the platform said at the last check: the bot's name and id and the scopes it refused. One
provider app serves one bot across every Tenant: the provider and the app's id are unique.

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
  `settings`) or
  `channel.delete`.

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
| `DELETE /api/channels/{id}` | Removes the bot and retires its account; `204` |

A bot's `status` is `OFF` when switched off; `NOT_RUNNING` when its supervisor has not written for three passes
(90 seconds), so no process holds it; else `CONNECTED` while its connection is open, `FAILED` after a failure to open
it, with the failure's typed code and time, or `CONNECTING`. A bot carries `region` for Lark and `transport` for
Zalo, each absent for the other. No response carries the secret or the token, and the request records redact them.

### The administration page

`/admin/channels` (`web/src/features/channels`, "Kênh chat"), shown to a holder of `CHANNELS_MANAGE` under the agents and tools
section, lists the bots as the MCP servers are listed: Lark's mark, the bot's name, one line and the switch, which
saves on change. The line names what is wrong first (no connection, the bots' process not running), then a scope Lark
refuses, else that the bot is off, connecting or answering; the list is read again every five seconds while a bot
switched on is not connected. Each bot has its page, `/admin/channels/{id}`: its status, Lark's name for the bot,
region, App ID, notice language, when it connected, its last message and its last failure, the scopes still missing,
and a menu to replace the App Secret or delete the bot. Adding a bot is a dialog beside the console steps and scopes
the app needs; the App Secret stays in a ref, never in query state or storage, and is cleared once sent.

## The adapter

A platform is a `ChannelAdapter`, one a `ChannelProvider`, found through `ChannelAdapters`; the port carries no
provider's shape. It owns the transport, the provider's ids and the shape of a reply, and states what the
provider's chats can do with `features()`: `ChannelFeatures(organisations, editsReply, maxText)`, whether the
provider names an organisation for each sender, whether a reply can be rewritten while it is written, and the
longest text one message takes. `reply(connection, message)` opens a `ChannelReply` that quotes the message where
the provider can: `append` text as it is written, called only when the reply can be rewritten; then
`finish(pieces, sources, footer)`, given the whole answer as one piece when the reply can be rewritten and otherwise
cut by `ChannelText.pieces` at `maxText` (at the last paragraph, line, sentence or space that fits), returning the
provider's id of the answer's last message; or `fail(notice)`. `notice` sends one short message; `sender` answers
a `ChannelSender` with the e-mail the provider holds, if any; `externalChat` says whether people outside the
organisation read a chat, false for a provider without organisations. It hands over a `ChannelMessage`: the
provider's message id, the chat and whether it is `DIRECT` or `GROUP`, the sender, the sender's organisation and
the bot's as the provider names them (none for a provider without organisations), whether the bot was named,
`TEXT` or `OTHER`, the text without the bot's mention, and the message it replies to.
`open(connection, secret, receiver)` starts receiving for one bot and returns a `ChannelTransport` (`connected()`,
`close()`), which either keeps a connection of its own or names the `ChannelWebhook` the provider pushes to:
`accept(headers, body)` checks the provider's secret before it reads the body and reads the messages, and
`register()` gives the provider the webhook's address (`ChannelWebhook.path`, `/hooks/channels/<connection id>`
under the public address) once it is served, failing with `CHANNEL_WEBHOOK_UNREACHABLE` when the provider's check
of the address did not arrive. The adapter acknowledges each message to the provider and hands it to the receiver,
which returns at once. `settings(requested)` checks a bot's settings. Lark's transport keeps a connection and Lark
states organisations, a reply it rewrites and 150,000 characters; Zalo's is below.

## The Zalo adapter

`ZaloChannelAdapter`, in `memoryos-integration-zalo` ([MEM-263](../increments/active/mem-263-zalo-bot/design.md)),
calls the Zalo Bot Platform (`memoryos.zalo.base-url`, `https://bot-api.zaloplatforms.com/bot<token>/<method>`)
through an `@HttpExchange` client on `shared.OutboundHttp`, since no SDK is maintained: 15 seconds a call, 45 for a
poll, 1 MiB. A failure is known by Zalo's `error_code`, else the status; its text, the address that holds the token
and the cause are never kept. Zalo states no organisations, no reply it rewrites and 2,000 characters.

- **A bot** is its token from Zalo Bot Creator; `getMe` names it. Its one setting is how it receives: `WEBHOOK`
  where the deployment has a public address (`memoryos.channel.web-url`), which is then the default, else
  `POLLING`; `WEBHOOK` without that address is `CHANNEL_WEB_URL_MISSING`.
- **Webhook.** Each opening makes a secret of 32 random bytes kept in memory; `register()` calls `setWebhook` with
  the bot's address and that secret and reads Zalo's check of the address from its answer (`testWebhook` when the
  answer carries none; a day's checks spent, `426`, is not held against the bot); a failed check is
  `CHANNEL_WEBHOOK_UNREACHABLE` with Zalo's outcome (`webhook.err.dns` and the like) in its diagnostic. A request is
  Zalo's when its `X-Bot-Api-Secret-Token` equals the secret, compared in constant time; a body of Zalo's that is no
  message is accepted and carries none. The transport asks `getWebhookInfo` at most once a minute whether the
  address is still this one, and `close()` calls `deleteWebhook`.
- **Polling.** Opening calls `deleteWebhook`, since Zalo serves no poll while a webhook is set, then one virtual
  thread polls `getUpdates` (30-second wait; `408` is an empty poll; an empty answer sooner than a second waits a
  second; errors back off from one second to a minute); a refused token stops it, and the supervisor opens it again.
- **A message** is `{event_name, message}`: `message.text.received` is `TEXT`, any other event `OTHER`; a sender
  Zalo marks as a bot is dropped. A `PRIVATE` chat is direct; in a `GROUP` (Zalo's beta) the bot is addressed when
  the text names it as `@<bot name>`, which is removed; Zalo documents no mention, which the probe of plan step 1
  settles. No sender e-mail, no external chat.
- **The reply** sends Zalo's typing mark at once and every four seconds until it ends. `finish` sends the pieces
  with `parse_mode: markdown`, the first after the asker's name in a group (Zalo has no quote), the sources as a
  numbered list of links and the closing line after the last piece or as a message of their own, none longer than
  2,000 characters; markdown Zalo refuses is sent again as plain text. `fail` and a notice are plain messages;
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
`deliver(id, headers, body)` hands a pushed request to that webhook (`UNKNOWN` when no bot here receives over HTTP,
`REFUSED` when it is not the provider's) and claims each message before it returns, so the provider is answered
only once its messages are recorded. Each message is answered on a virtual thread of its own. Stopping closes every
connection, then waits up to 30 seconds for the answers in progress.

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
   nobody, and a provider without organisations admits nobody until linked accounts exist), when it is not text (`NOT_TEXT`) or when the group has people outside the organisation
   (`EXTERNAL_GROUP`).
4. The sender's account is the one active person whose verified e-mail equals the e-mail the provider holds
   ([identity](identity.md#account-classification-and-group-authority)), remembered in `channel_identities` for
   `memoryos.channel.identity-ttl` (1 hour); a sender without one is remembered as such.
5. A direct message from a sender with an account runs as that person. A group message, and a direct message from
   a sender without an account, runs as the bot's service account, which reads what every member may read.
6. The conversation: a reply to a message, the question or the bot's answer, continues that message's session
   however old and whoever replies; a new message in a group starts a session; a new direct message continues
   the one in `channel_conversations` while its last message is younger than `memoryos.channel.conversation-idle`
   (1 hour); `/new` in a direct message forgets it and says so. A session is continued only by the account that
   owns it, so a sender who gained or lost an account starts over.
7. The question becomes a Chat turn through the API the web uses: a session created with
   `ChatSessionService.createInChannel`, a `SEND` at `ChatSessionService.leaf` with a request id derived from
   the provider's message id, the reply's stream read for text, and the saved answer read for its text and
   sources. The answer is finished with a closing line in a group ("reply to continue, send a direct message for
   your own documents") and for a sender without an account ("sign in").

A failure is a notice and a recorded reason, never a provider message or the question: a refused or busy turn
says "busy", a spend limit names the limit, anything else says the bot could not answer. Notices are in the
connection's language.

A claimed message is one `memoryos.channel.message` observation (`provider`, `kind`, `outcome`: `ANSWERED`,
`REFUSED`, `NEW_CONVERSATION` or `FAILED`), with a child `memoryos.channel.message.step` for each step (`step`:
`refusal`, `sender`, `conversation`, `reply`, `turn`, `finish`); the Chat turn's spans sit under `turn`, so a trace
shows where the time before an answer goes.

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

- **The way to the web.** A direct answer that ran as the sender ends with a link, "Continue in MemoryOS", to the
  same session at `memoryos.channel.web-url` (`MEMORYOS_CHANNEL_WEB_URL`) + `/chat/{session}`, which opens and continues
  there although it is not in the sidebar; with no address configured there is no link. A group's session is the
  bot's, so a group answer has none.
