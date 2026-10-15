# MEM-263 Zalo bot

A member asks the MemoryOS bot in Zalo, in a direct message or by naming it in a group, and is answered from what
they may read. It is the second provider of the `channel` capability that [MEM-246](../mem-246-lark-bot/design.md)
built for Lark; everything that capability decides (who a turn runs as, conversations, order, the sender's rate,
notices, the bounds) holds here unless this page says otherwise.

## Goal and scope

Built and tested on staging: the Zalo Bot Platform (`bot-api.zaloplatforms.com`), the bot a person creates in the
Zalo app with "Zalo Bot Creator". The `channel` port is reshaped first so that it carries no Lark shape, as the
reference gateways keep one adapter a platform behind a neutral interface; Zalo OA, Telegram and others then plug in
as adapters. Only Zalo Bot is built; no enum value or adapter exists for a provider before it is built.

Decided with the owner (2026-10-15): a sender who has not linked an account gets only the linking instructions;
both webhook and polling, chosen by the administrator for each bot, webhook by default; staging only; choosing an agent for a bot
is a separate increment after this one.

## How the references do it (read 2026-10-15)

| | Shared interface | Platform differences | Inbound HTTP | Unknown sender |
| --- | --- | --- | --- | --- |
| [OpenClaw](https://raw.githubusercontent.com/openclaw/openclaw/main/src/channels/plugins/types.plugin.ts) | `ChannelPlugin`: optional sub-adapters (config, outbound, security, pairing, streaming, gateway) | A `capabilities` object (`edit`, `reactions`, `threads`, `blockStreaming`…); the adapter states its chunk limit, shared delivery chunks | The core serves the route, the plugin checks the signature | The core issues a pairing code, the owner approves; messages ignored until then |
| [GoClaw](https://raw.githubusercontent.com/nextlevelbuilder/goclaw/main/internal/channels/channel.go) | `Channel`: `Start`, `Stop`, `Send`, `IsAllowed` | Optional interfaces: `StreamingChannel`, `WebhookChannel`, `ReactionChannel` | The core mounts each `WebhookChannel`'s handler | The core issues a code, an administrator approves; the notice at most once a minute |
| [Hermes Agent](https://github.com/NousResearch/hermes-agent/blob/main/gateway/platforms/base.py) | `BasePlatformAdapter`: `connect`, `send`, `edit_message`… | Class attributes: `MAX_MESSAGE_LENGTH`, `supports_draft_streaming` | The adapter runs its listener | The core issues a code (hashed, locked after five failures), the owner approves |
| [WeKnora](https://raw.githubusercontent.com/Tencent/WeKnora/main/internal/im/adapter.go) | `Adapter`: `VerifyCallback`, `ParseCallback`, `SendReply` | An optional `StreamSender` for platforms that edit | The core receives, the adapter verifies and parses | Not found |
| [RAGFlow](https://raw.githubusercontent.com/infiniflow/ragflow/main/internal/channels/core/channel.go) | `Channel`: `Start`, `Stop`, `Send`, `SetMessageHandler` | None | The adapter | Not found |

Zalo itself: OpenClaw's official `zalo` plugin and GoClaw's channel misnamed "Zalo OA" both call the Zalo Bot API;
both also ship a personal account over an unofficial protocol (`zca-js`, zcago), which OpenClaw warns may get the
account banned and is not built here. Neither implements the Zalo Official Account API. OpenClaw's plugin polls
by default and uses a webhook when one is configured, gates groups on a mention ("not configurable"), sends the
answer in 2,000-character blocks, deduplicates by `message_id` and "returns HTTP 200 only after it durably stores
the raw event" ([OpenClaw Zalo](https://docs.openclaw.ai/channels/zalo.md)).

## What Zalo gives

| Question | Answer | Source |
| --- | --- | --- |
| Calls | `getMe`, `getUpdates`, `setWebhook`, `testWebhook`, `deleteWebhook`, `getWebhookInfo`, `sendMessage`, `sendPhoto`, `sendSticker`, `sendVoice`, `sendChatAction` at `https://bot-api.zaloplatforms.com/bot<token>/<method>`, answering `{ok, result, description, error_code}` | [bot.zapps.me/docs](https://bot.zapps.me/docs/call-api/) |
| Credential | One token `<bot id>:<secret>`, valid until reset in Bot Creator | [authorize](https://bot.zapps.me/docs/authorize/) |
| Editing a sent message | No call | [API list](https://bot.zapps.me/docs) |
| Text | 1–2,000 characters, `parse_mode` `markdown` or `html` | [sendMessage](https://bot.zapps.me/docs/apis/sendMessage/) |
| Typing | `sendChatAction` `typing` | [sendChatAction](https://bot.zapps.me/docs/apis/sendChatAction/) |
| A received message | `event_name` (`message.text.received`, image, sticker, voice, unsupported), `message.from{id, display_name, is_bot}`, `chat{id, chat_type PRIVATE or GROUP (beta)}`, `text`, `message_id`, `date` | [webhook](https://bot.zapps.me/docs/webhook/) |
| The sender's e-mail or phone | Not given | same |
| Groups | Beta; how the mention appears in the text is not documented | same |
| Webhook | Public HTTPS; `secret_token` of 8–256 characters echoed in `X-Bot-Api-Secret-Token`; retries and duplicates not documented | [setWebhook](https://bot.zapps.me/docs/apis/setWebhook/) |
| Polling | Excludes a webhook; "Chỉ nên sử dụng API này để chạy local, trong môi trường development, thử nghiệm… với môi trường production, bạn nên thiết lập Webhook để tránh bỏ lỡ event"; 30-second timeout; one update a call | [getUpdates](https://bot.zapps.me/docs/apis/getUpdates/) |
| Free plan | 3 bots an account, 50 users a bot, 3,000 messages a month, 3 groups; past it `429 Quota exceeded`; larger volumes pointed to Zalo OA | [bot.zapps.me](https://bot.zapps.me/), [error codes](https://bot.zapps.me/docs/error-code/) |
| Terms | Individuals 18+ or organisations; legitimate business use; users told they talk to an automated system | [terms](https://bot.zapps.me/docs/terms/) |
| Java SDK | None official; `dev.linhvu:zalobot-sdk-java` 0.0.2 is one person's | Maven Central |

The free plan fits a pilot, not a company; nothing here depends on the plan.

## Decisions

### The port carries no Lark shape

Four parts of today's `ChannelAdapter` are Lark's and change before Zalo is added; Lark's behaviour does not.

1. **Credentials.** `region` and the `verify(region, appId, secret)` signature become the provider's: a connection
   keeps the provider's id of its app (`app_id`), the provider's public settings (`settings`, JSON; Lark's region)
   and the encrypted secret (Lark's App Secret, Zalo's token). `verify(ChannelCredentials)` takes them and answers
   the normalised app id with the bot's name, so Zalo's id comes from its token. The API keeps a typed `region`,
   required for Lark and refused for others.
2. **Features, stated.** `features()` answers `ChannelFeatures(organisations, editsReply, maxText)`: whether the
   provider names an organisation for each sender, whether a reply can be rewritten while it is written, and the
   longest text one message takes. The inbound service reads the first for its admission rule; a provider that does
   not edit never sees `append`, and its `finish` is given the answer cut by a shared splitter at `maxText`
   (paragraph, line, sentence, then hard ends), as OpenClaw's shared delivery chunks and GoClaw's shared helper
   does. Lark: organisations, edits, 150,000. Zalo: none, no, 2,000.
3. **Inbound transport.** An adapter's transport either keeps a connection (Lark's WebSocket, Zalo's polling) or
   receives over HTTP. The second is an optional `ChannelWebhook` the adapter returns from `open`: `accept(headers,
   body)` checks the provider's secret and parses, as WeKnora's `VerifyCallback`/`ParseCallback`; the route and the
   claim are the core's (below).
4. **The sender.** `senderEmail` and the organisation keys on `ChannelMessage` become `ChannelMessage.organisation`
   (nullable, with the bot's) and `sender(connection, id)` answering `ChannelSender(email)`, empty when the provider
   gives none. `externalChat` stays, false for a provider without organisations.

### A linked account instead of an e-mail

Zalo names neither e-mail nor organisation, and anyone on Zalo can message a bot. The bot answers only members who
linked their Zalo account to their MemoryOS account. As in every reference, a stranger is told how to be let in and
nothing else; unlike them, MemoryOS has many members, each of whom must reach their own documents, so no
administrator approves a code: the member links their own account.

The link starts in MemoryOS: Settings › Chat shows "Liên kết Zalo" (one row a Zalo bot of the Tenant) with a code of
eight characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no 0, O, 1, I, as OpenClaw and Hermes; 40 bits) valid ten
minutes; the member sends it to the bot; the Zalo account that sends a live code is linked to the account that made
it, and the bot confirms. Starting in Zalo instead (the bot sends a link or a code the member confirms on the web)
lets someone forward their own link or code to a colleague, whose confirmation gives the forwarder the colleague's
documents; a code made in a signed-in session can only be misused by its owner handing it over. The code is stored
as a SHA-256 hash, single use, one live code a member and bot. A sender with five wrong codes in an hour is not
heard for the rest of the hour (Hermes locks the same way); the linking notice goes to one sender at most once a
minute (GoClaw).

A link belongs to one bot (a Zalo user id is not known to be the same across bots), is durable (no expiry, unlike
the e-mail answers Lark's adapter remembers for an hour), and is shown in Settings › Chat with "Bỏ liên kết". It is
removed with the member's membership or the bot; one Zalo account links to one member and a member to one Zalo
account a bot. A member who links another account of theirs replaces their old link; an account linked to another member is not taken by a code until that member unlinks it, so an account never changes hands without its member knowing (a security review of 2026-10-15). The bot's page shows how many members linked, not who.

The link is the capability's: any provider whose sender has no e-mail uses it. `ChannelInboundService.sender`
reads a link first, then the e-mail.

### Who is answered

For a provider without organisations: a direct message from an unlinked sender is answered with the linking notice;
a message that is a live code links its sender; a group question is answered only when its sender is linked, from
what the bot's account may read, as in Lark, and an unlinked sender in a group gets the linking notice. Every
answer is thus paid for by a member of the Tenant, and a stranger who adds the bot to a group gets nothing.

### Transport: webhook or polling, chosen for each bot

The administrator chooses for each bot, in the add dialog and on the bot's page (saved on change), "Webhook" (the
default) or "Polling"; it is kept in the bot's `settings` (`transport`). OpenClaw configures the same per bot
account. Polling is for a host Zalo cannot call, such as a customer's internal cluster or a developer's machine.
Both are permanent ways of running, not a temporary mode. A webhook bot on a host Zalo cannot reach is not left
silent: switching it on runs `testWebhook`, and a failure keeps the bot off with `CHANNEL_WEBHOOK_UNREACHABLE` and
Zalo's outcome (`webhook.err.dns`, `webhook.err.tls`, `webhook.err.timeout`, `webhook.http.403`…) on its page.

- **Webhook.** `open` calls `setWebhook` with `<memoryos.channel.web-url>/hooks/channels/<connection id>` and a
  secret of 32 random bytes made for that opening and kept in memory; `close` calls `deleteWebhook`.
  `memoryos-channels` serves `POST /hooks/channels/{id}`: it finds the open transport (`404` for none), caps the
  body at 64 KB, lets the adapter check the secret header in constant time before reading the body (`403` on a
  mismatch) and parse it, and answers `200` once the message's claim in `channel_messages` is written, as OpenClaw
  answers only after storing the event; a message pushed twice is answered once. The turn runs after the answer.
  The web's nginx routes `/hooks/channels/` to `memoryos-channels:8082`. `connected()` is whether
  `getWebhookInfo` still names this opening's address, read at most once a minute. Webhook is not offered, and a
  webhook bot cannot be switched on, while `memoryos.channel.web-url` is empty (`CHANNEL_WEB_URL_MISSING`).
- **Polling.** `open` calls `deleteWebhook`, then one virtual thread loops on `getUpdates` (30-second timeout, `408`
  an empty poll, backoff on errors) and hands each message to the receiver; `close` stops it. The runbook states
  Zalo's own warning that polling may miss events.

One process opens a bot, as today.

### The reply

Zalo cannot edit a message. `reply` sends `typing` at once and again every four seconds while the turn runs;
`finish` sends the pieces the splitter cut, the last carrying the sources (titles with their addresses) and the
footer, with `parse_mode: markdown`. Zalo has no quote, so in a group the answer starts with the asker's name.
`fail` sends the notice; `acknowledge` sends `typing` (no reactions). A `429` from Zalo is the bot's quota: the reply
is dropped, the message settles `FAILED` with `CHANNEL_PROVIDER_QUOTA`, and the bot's page shows it as its last
failure.

### The provider and the module

`ChannelProvider.ZALO_BOT` (an OA adapter would be `ZALO_OA`, another API). The adapter is in a new module
`memoryos-integration-zalo`, package `io.memoryos.integration.zalo`, by
[ADR 0027](../../../decisions/0027-integrations-by-platform-and-memoryos-module-names.md), with its dependency rule
test and `checkRuntimeClasspathIsolation`. No SDK is maintained, so the client is an `@HttpExchange` interface on
`shared.OutboundHttp`. `verify` calls `getMe` and stores `account_name`.

### The web

"Thêm bot" asks Lark or Zalo first; Zalo asks a name, the token and the transport. The bot's page shows its facts as for Lark, with
the linked-member count. Settings › Chat gains the Zalo link rows.

## Contracts

- `/api/channels`: `provider` `LARK` or `ZALO_BOT`; `appId` and `region` for Lark only; `secret` is Lark's App
  Secret or Zalo's token.
- `POST /api/channel-links` (`connectionId`) answers `{code, expiresAt}`; `GET /api/channel-links` lists the
  member's links and the Tenant's bots that take one; `DELETE /api/channel-links/{connectionId}` removes one.
- `POST /hooks/channels/{connectionId}` on `memoryos-channels`, outside `/api`, no session.
- `transport` (`WEBHOOK` or `POLLING`) on a Zalo bot, at creation and by `PATCH`.
- New codes: `CHANNEL_WEB_URL_MISSING`, `CHANNEL_WEBHOOK_UNREACHABLE`, `CHANNEL_PROVIDER_QUOTA`. New notices: `LINK_REQUIRED`, `LINKED`,
  `LINK_CODE_INVALID`.

## Data

- `channel_connections.region` becomes `settings JSONB NOT NULL DEFAULT '{}'`, Lark's rows carrying their region, Zalo's their transport.
- `channel_link_codes (tenant_id, connection_id, actor_id, code_hash, expires_at)`, one row a member and bot.
- `channel_account_links (tenant_id, connection_id, external_user_id, actor_id, linked_at)`, unique on
  `(connection_id, external_user_id)` and on `(connection_id, actor_id)`; cascades from the connection and the
  membership.
- One app serves one bot by `(provider, app_id)` alone: the settings, where a Zalo bot keeps its transport, are no
  part of it, so one bot is never stored twice with two transports.

## Not in scope

- Zalo OA, Telegram, WhatsApp, a Zalo personal account; images, stickers and voice (the text-only notice, as Lark).
- An agent chosen for a bot (the next increment); production.
- An administrator listing or removing members' links.

## Risks

- Group behaviour is beta and undocumented: whether every group message or only a mention arrives, and how the
  mention reads. Settled by probing the owner's bot (plan step 1); until then a group message is addressed only when
  it names the bot.
- Webhook retries and ordering are undocumented; the claim deduplicates.
- The quota is per month and per bot; a busy pilot stops answering until the month ends.
