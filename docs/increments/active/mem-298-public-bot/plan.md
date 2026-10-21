# MEM-298 plan

Two pull requests after this design: the backend with its API, then the page and the editor's warning.

## PR 1: core and API

- [ ] Migration: `channel_connections.public_access boolean NOT NULL DEFAULT false`.
- [ ] `ChannelConnection.publicAccess`; `ChannelConnectionService.setPublic` (`CHANNELS_MANAGE`, revision check,
  exclusive lock, audited `public` / `private`, revision moved).
- [ ] `ChannelInboundService`: for a public bot, no linking step, no organisation check, no group refusal; the turn
  always as the bot's account, the asker's name on every question, no footer; the group still recorded.
- [ ] `ChatChannel` says whether the bot is public; for such a turn Chat restricts search to the assistant's
  attachments even when there are none, and offers neither the code interpreter nor image generation.
- [ ] Connector: a search scope over given Sources for the bot's account, which reads a Public or Private Source whole
  and an Auto Sync Source as the account may; Search, citation reads and the reply's sources use it on the same turn.
  Check that the assistant's files are read on the bot account's turn as today.
- [ ] API: `PUT /api/channels/{channelId}/public`, `public` on the bot's response and on the assistant's channel
  listing; `openapi.yml` and the Hey API client.
- [ ] Tests: an unlinked Zalo or WhatsApp sender and an outsider of Lark are answered by a public bot, in a direct
  message and in a group, and refused again once it is private; a Zalo group needs no permission; a linked member's
  direct question runs as the bot; the turn searches only the attachments (a Private Source the bot account cannot
  read is found, an unattached one is not, an Auto Sync document the account cannot read is not); an assistant with no
  attachments searches nothing; code interpreter and image generation are not offered; switching takes the capability
  and the revision and is audited.

## PR 2: web

- [ ] *Công khai* switch on the bot page with the confirmation dialog naming the assistants and what they are given;
  switching off needs no dialog.
- [ ] The assistant editor's warning beside its knowledge section while a public bot answers with it.
- [ ] i18n (vi, en), component tests, screenshots at desktop and phone width, light and dark.

## Docs

- [ ] Channel spec (a public bot), identity spec (what the bot account reads on a public bot), chat spec (the turn's
  restrictions), test matrices, roadmap.

## Acceptance on staging

- [ ] A Zalo account not linked asks the public staging bot directly and in a group and is answered from the
  assistant's attachments only; a document outside them is not found; switched back to private, it is told to link.
  Needs the owner to send the messages.
