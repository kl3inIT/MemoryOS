# Implementation plan

Design: [design.md](design.md). Each pull request is accepted when CI is green and staging deployed it.

## 1. Probe a real bot

- [ ] With the owner's bot: `getMe`, a direct text, an image, a sticker, the bot named in a group and a group
  message that does not name it, over polling and over a webhook on staging; `sendMessage` with
  `parse_mode: markdown`, `sendChatAction`, a 2,000-character piece; record the payloads (ids only) and how a
  mention reads in the design.

## 2. The port without Lark's shape

- [x] `ChannelCredentials`, `settings` in place of `region` (migration V165), `verify` answering the app id;
  `ChannelFeatures` and the shared splitter; `ChannelWebhook`; `ChannelSender` and the nullable organisation.
  Lark's adapter, the API and the web keep their behaviour; existing tests stay green and new ones cover the
  splitter and the admission rule for a provider without organisations.

## 3. Linked accounts

- [x] Migration: `channel_link_codes`, `channel_account_links`.
- [x] The link service: make a code, redeem it from a message, the five-failure lock, the once-a-minute notice,
  list and remove a member's links; removal with the membership and the bot.
- [x] `ChannelInboundService`: the link before the e-mail; the rules for a provider without organisations.
- [x] `/api/channel-links` with contract records; `openapi.yml` and the web client.
- [x] Tests at the PostgreSQL boundary and of the inbound rules; the channel spec and matrix.

## 4. The Zalo adapter

- [ ] `memoryos-integration-zalo`: the `@HttpExchange` client, `verify` by `getMe`, the webhook transport and the
  polling transport, the conversion of an update, the reply with typing, pieces, sources and footer, `429` as
  `CHANNEL_PROVIDER_QUOTA`; dependency rule test and classpath isolation.
- [ ] `memoryos-channels`: `POST /hooks/channels/{id}` answering after the claim; the web nginx route;
  `memoryos.channel.zalo.transport`; `CHANNEL_WEB_URL_MISSING`; compose and the staging example.
- [ ] `/api/channels` for `ZALO_BOT`; contract, `openapi.yml`, the web client.

## 5. The web

- [ ] "Thêm bot" with the provider first; the Zalo form; the linked count on the bot's page; Settings › Chat link
  rows with code and "Bỏ liên kết"; both languages; e2e and screenshots at desktop and phone widths.

## 6. Acceptance

- [ ] On staging with the owner's bot: link, a direct question on a private document, an unlinked sender, a group
  question, a long answer in pieces, unlink; a restart of `memoryos-channels` re-registers the webhook; the same
  over polling.
- [ ] `verification.md`; channel spec, matrix, runbook (`docs/runbooks/zalo-bot.md`), ARCHITECTURE; roadmap.
