# Implementation plan

Design: [design.md](design.md). Each pull request is accepted when CI is green and staging deployed it.

## 1. Probe a real bot

- [ ] With a bot the owner creates in Zalo Bot Creator: `getMe`, `setWebhook` to a probe address, a direct text,
  an image, a sticker, the bot named in a group and a message in a group that does not name it, `sendMessage` with
  `parse_mode: markdown`, `sendChatAction`, a 2,000-character piece; record the payloads (ids only) and how a
  mention reads in the design.

## 2. Linked accounts in `channel`

- [ ] Migration: `channel_link_codes`, `channel_account_links`, `region` nullable with the Lark check.
- [ ] The link service: make a code (hash, ten minutes, one live a member and bot), redeem it from a message,
  list and remove a member's links; removal with the membership.
- [ ] `ChannelInboundService`: the link before the e-mail; an adapter's `organisations()`; for a provider without
  them the unlinked direct sender gets `LINK_REQUIRED`, a live code links, a group question needs a linked sender.
- [ ] `/api/channel-links` with contract records; `openapi.yml` and the web client.
- [ ] Tests at the PostgreSQL boundary and of the inbound rules; the channel spec and matrix.

## 3. The Zalo adapter

- [ ] `memoryos-integration-zalo`: the `@HttpExchange` client, `verify` by `getMe`, the webhook transport with
  `setWebhook`/`deleteWebhook`/`getWebhookInfo`, the conversion of an update, the non-streaming reply with typing,
  pieces, sources and footer, `429` as `CHANNEL_PROVIDER_QUOTA`; dependency rule test and classpath isolation.
- [ ] `memoryos-channels`: `POST /hooks/channels/{id}` with the secret check and body cap; the web nginx route;
  `CHANNEL_WEB_URL_MISSING`.
- [ ] `/api/channels` for `ZALO_BOT`; contract, `openapi.yml`, the web client.

## 4. The web

- [ ] "Thêm bot" with the provider first; the Zalo form; the linked count on the bot's page; Settings › Chat link
  rows with code and "Bỏ liên kết"; both languages; e2e and screenshots at desktop and phone widths.

## 5. Acceptance

- [ ] On staging with the owner's bot: link, a direct question on a private document, an unlinked sender, a
  group question, a long answer in pieces, unlink; a restart of `memoryos-channels` re-registers the webhook.
- [ ] `verification.md`; channel spec, matrix, runbook (`docs/runbooks/zalo-bot.md`), ARCHITECTURE; roadmap.
