# MEM-263 Zalo bot

A member asks the MemoryOS bot in Zalo, in a direct message or by naming it in a group, and is answered from what
they may read. It is the second provider of the `channel` capability that [MEM-246](../mem-246-lark-bot/design.md)
built for Lark; everything that capability decides (who a turn runs as, conversations, order, the sender's rate,
notices, the bounds) holds here unless this page says otherwise.

## Goal and scope

Built and tested: the Zalo Bot Platform (`bot-api.zaloplatforms.com`), the bot a person creates in the Zalo app
with "Zalo Bot Creator". Shaped so other providers plug in as adapters, as OpenClaw and GoClaw ship Zalo Bot, Zalo
OA, Telegram and WhatsApp behind one channel layer: the account link below serves any provider that gives no
e-mail, and the webhook route any provider that pushes over HTTP. Zalo OA, Telegram and WhatsApp themselves are not
built (no adapter, no enum value) until one is needed.

## What Zalo gives (researched 2026-10-15)

| Question | Answer | Source |
| --- | --- | --- |
| Calls | `getMe`, `getUpdates`, `setWebhook`, `testWebhook`, `deleteWebhook`, `getWebhookInfo`, `sendMessage`, `sendPhoto`, `sendSticker`, `sendVoice`, `sendChatAction`; `https://bot-api.zaloplatforms.com/bot<token>/<method>`, answers `{ok, result, description, error_code}` | [bot.zapps.me/docs](https://bot.zapps.me/docs/call-api/) |
| Credential | One token `<bot id>:<secret>`, valid until reset in Bot Creator | [authorize](https://bot.zapps.me/docs/authorize/) |
| Editing a sent message | No call; OpenClaw sends the answer in 2,000-character pieces | [API list](https://bot.zapps.me/docs), [OpenClaw](https://docs.openclaw.ai/channels/zalo.md) |
| Text | 1–2,000 characters, `parse_mode` `markdown` or `html` | [sendMessage](https://bot.zapps.me/docs/apis/sendMessage/) |
| Typing | `sendChatAction` `typing` | [sendChatAction](https://bot.zapps.me/docs/apis/sendChatAction/) |
| A received message | `event_name` (`message.text.received`, image, sticker, voice, unsupported), `message.from{id, display_name, is_bot}`, `chat{id, chat_type PRIVATE or GROUP (beta)}`, `text`, `message_id`, `date` | [webhook](https://bot.zapps.me/docs/webhook/) |
| The sender's e-mail or phone | Not given | same |
| Groups | Beta; the bot answers when named (OpenClaw: "not configurable"); how the mention appears in the text is not documented | [webhook](https://bot.zapps.me/docs/webhook/), [OpenClaw](https://docs.openclaw.ai/channels/zalo.md) |
| Webhook | Public HTTPS; `secret_token` of 8–256 characters echoed in `X-Bot-Api-Secret-Token`; answer 2xx at once; retries and duplicates not documented | [setWebhook](https://bot.zapps.me/docs/apis/setWebhook/) |
| Polling | `getUpdates` does not work while a webhook is set and is meant for development | [getUpdates](https://bot.zapps.me/docs/apis/getUpdates/) |
| Free plan | 3 bots an account, 50 users a bot, 3,000 messages a month, 3 groups; past the quota `429 Quota exceeded`; "Pro" announced, larger volumes pointed to Zalo OA | [bot.zapps.me](https://bot.zapps.me/), [error codes](https://bot.zapps.me/docs/error-code/) |
| Terms | Individuals 18+ or organisations; legitimate business use; users told they talk to an automated system | [terms](https://bot.zapps.me/docs/terms/) |
| Java SDK | None official; `dev.linhvu:zalobot-sdk-java` 0.0.2 is one person's | Maven Central |

The free plan's 50 users and 3,000 messages fit a pilot, not a company; the design does not depend on the plan, and
a Tenant past it moves to the paid plan or, later, a Zalo OA adapter.

## Decisions

### A linked account instead of an e-mail

Lark names the sender's e-mail and the organisation; Zalo names neither, and anyone on Zalo can message a bot. The
bot therefore answers only members who linked their Zalo account to their MemoryOS account, and tells anyone else
how to link.

The link starts in MemoryOS, as GitHub's and Slack's apps start theirs in the signed-in product: Settings › Chat
shows "Liên kết Zalo" (one row a Zalo bot of the Tenant) with a code of eight characters (Crockford base32, 40
bits) valid ten minutes; the member sends it to the bot; the Zalo account that sends a live code is linked to the
account that made it, and the bot confirms. Starting in Zalo instead (the bot sends a link that the member opens
and confirms) lets someone forward their own link to a colleague, whose confirmation would give the forwarder the
colleague's documents; a code made in a signed-in session can only be misused by its owner handing it over. The
code is stored as a SHA-256 hash, single use, one live code a member and bot; a wrong code counts against the
sender's rate like any message.

A link belongs to one bot (a Zalo user id is not known to be the same across bots), is durable (no expiry, unlike
the e-mail answers Lark's adapter remembers for an hour), and is shown in Settings › Chat with "Bỏ liên kết". It is
removed with the member's membership, and the bot's page shows how many members linked, not who. One Zalo account
links to one member and a member to one Zalo account a bot; linking again replaces the old link.

This is the capability's, not Zalo's: a provider whose `senderEmail` is always empty (Zalo OA, Telegram) uses the
same link. `ChannelInboundService.sender` reads a link first, then the e-mail.

### Who is answered

The organisation rule of MEM-246 becomes the provider's: an adapter declares whether its provider has
organisations. Lark keeps the rule; for Zalo a direct message from an unlinked sender is answered with the link
notice (and nothing else), a message that is a live code links its sender, and a group question is answered only
when its sender is linked, from what the bot's account may read, as in Lark. So every answer is paid for by a
member of the Tenant, and a stranger who adds the bot to a group gets nothing.

### Transport: a webhook on the public address

Zalo pushes by webhook in production. `open` calls `setWebhook` with
`<memoryos.channel.web-url>/hooks/channels/<connection id>` and a secret of 32 random bytes made for that
opening, kept in memory; `close` calls `deleteWebhook`. `memoryos-channels` serves `POST /hooks/channels/{id}`:
it finds the open transport, compares the secret header in constant time (`403` on mismatch, `404` for no open
transport), caps the body at 64 KB, hands the message to the receiver and answers `200` at once; the claim in
`channel_messages` by `message_id` answers a message pushed twice once, as for Lark. The web's nginx routes
`/hooks/channels/` to `memoryos-channels:8082`. `connected()` is whether `getWebhookInfo` still names this
opening's address, read at most once a minute. A bot without `memoryos.channel.web-url` set cannot be switched on
(`CHANNEL_WEB_URL_MISSING`).

Polling with `getUpdates` is not built: Zalo calls it a development aid, and keeping both would be the second
runtime mode ADR 0002 refuses. One process opens a bot, as today; a second `memoryos-channels` replica would move
the webhook to itself, which the supervisor's single opener already prevents.

### The reply

Zalo cannot edit a message, so `reply` sends `typing` at once and again every four seconds while the turn runs,
`append` keeps the text, and `finish` sends the answer in pieces of at most 2,000 characters split at paragraph,
then line, then sentence ends, the last piece carrying the sources (titles with their addresses) and the footer.
Markdown is sent with `parse_mode: markdown`. A reply cannot quote the question (Zalo has none), so in a group the
answer starts with the asker's name. `fail` sends the notice. `acknowledge` sends `typing` (Zalo has no reactions).
A `429` from Zalo is the bot's quota: the reply is dropped, the message settles `FAILED` with
`CHANNEL_PROVIDER_QUOTA`, and the bot's page shows it as its last failure.

### The provider and the module

`ChannelProvider.ZALO_BOT` (Zalo OA would be `ZALO_OA`, another API). The adapter is in a new module
`memoryos-integration-zalo`, package `io.memoryos.integration.zalo`, by [ADR 0027](../../../decisions/0027-integrations-by-platform-and-memoryos-module-names.md),
with its dependency rule test and `checkRuntimeClasspathIsolation`. No SDK is maintained, so the client is an
`@HttpExchange` interface on `shared.OutboundHttp`. The token is the connection's secret, encrypted as Lark's is;
`appId` is the bot id before the colon; `region` is null for a provider without regions. `verify` calls `getMe`
and stores the bot's `account_name`; `missingScopes` is empty.

### The web

"Thêm bot" asks Lark or Zalo first; Zalo asks a name and the token. The bot's page shows its facts as for Lark,
with the linked-member count. Settings › Chat gains the Zalo link rows.

## Contracts

- `/api/channels` accepts `provider: ZALO_BOT` with `token`; `region` is required for Lark only.
- `POST /api/channel-links` (`connectionId`) answers `{code, expiresAt}`; `GET /api/channel-links` lists the
  member's links; `DELETE /api/channel-links/{connectionId}` removes one.
- `POST /hooks/channels/{connectionId}` on `memoryos-channels`, not under `/api`, no session; the secret header.
- New codes: `CHANNEL_WEB_URL_MISSING`, `CHANNEL_PROVIDER_QUOTA`. New notices: `LINK_REQUIRED`, `LINKED`,
  `LINK_CODE_INVALID`.

## Data

- `channel_link_codes (tenant_id, connection_id, actor_id, code_hash, expires_at)`, one row a member and bot.
- `channel_account_links (tenant_id, connection_id, external_user_id, actor_id, linked_at)`, unique on
  `(connection_id, external_user_id)` and on `(connection_id, actor_id)`; cascades from the connection and the
  membership.
- `channel_connections.region` becomes nullable, with a check that Lark has one.

## Not in scope

- Zalo OA, Telegram, WhatsApp; polling; images, stickers and voice in either direction (answered with the text
  notice, as Lark).
- An agent chosen for a bot (the next increment after this one).
- An administrator listing or removing members' links.

## Risks

- Group behaviour is beta and undocumented: whether every group message or only a mention arrives, and how the
  mention reads. Settled by probing a real bot first (plan step 1); the adapter treats a group message as addressed
  only when it names the bot.
- Webhook retries and ordering are undocumented: a slow answer to Zalo's POST could be pushed again; the handler
  answers before the turn and the claim deduplicates.
- The quota is per month and per bot; a busy pilot stops answering until the month ends.
