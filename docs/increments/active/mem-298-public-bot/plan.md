# MEM-298 plan

Two pull requests after this design, each merged when CI is green on its head: the backend with its API, then the
bot page and the editor's warning. Acceptance on staging follows the second.

## PR 1: core and API

Storage and the switch:

- [ ] Migration V194: `channel_connections.public_access boolean NOT NULL DEFAULT false`.
- [ ] `ChannelConnection.publicAccess`, read by the connection repository and the bot's view.
- [ ] `ChannelConnectionService.setPublic(actor, channel, revision, boolean)`: `CHANNELS_MANAGE`, the exclusive lock,
  the revision check (`CHANNEL_CHANGED`), audited `public` / `private`, the revision moved; the bot's recent
  conversations (`JdbcChannelInboundRepository`, every chat of the bot) are dropped in the same transaction, so the
  next message starts over (decision 1).

What a message gets (`ChannelInboundService`):

- [ ] A public bot skips the linking step and the sender and group refusals (`OUTSIDER`, `EXTERNAL_GROUP`,
  `UNLINKED_IN_GROUP`, `GROUP_NOT_ALLOWED`); the per-sender rate, empty, text-only and the guardrails stay. A Zalo
  group is still recorded on the bot's page.
- [ ] The turn runs as the bot's account in a direct message too, the question labelled with the asker's name
  (`asked`); no closing line; citations without their link (decision 3, 4).

What the turn may read and use (Chat, connector):

- [ ] `ChatChannel` carries whether the bot is public. For such a turn `ChatTurnService` restricts search to the
  assistant's attachments even when it has none, and turns the code interpreter off.
- [ ] Connector: `SourceSearchService.attachedScope(actor, sourceIds)`, the scope of a public bot's turn: the given
  Sources that are not being deleted; a Public or Private one with every document, an Auto Sync one with the bot
  account's own access tokens. `SearchTool` uses it for such a turn, and the citation read and the reply's sources go
  through the same check, so a document found is also one the turn may cite.
- [ ] Check that the assistant's files are read on the bot account's turn, as they are in a group today.

API:

- [ ] `PUT /api/channels/{channelId}/public` (`setChannelPublic`, `UpdateChannelPublicRequest{boolean public}`, the
  revision checked as `enabled` checks it), `public` on `ChannelResponse` and on `AssistantChannelResponse`;
  `openapi.yml`, the contract test's path list and the Hey API client.

Tests:

- [ ] `ChannelInboundServiceTest`: a public bot answers an unlinked Zalo and WhatsApp sender and a Lark outsider, in a
  direct message and in a group, as the bot, without a footer or citation links; a Zalo group needs no permission; a
  linked member's direct question runs as the bot; once private, the same senders are told to link or refused.
- [ ] `PostgresChannelConnectionTest`: switching takes the capability and the revision, is audited and drops the bot's
  conversations.
- [ ] Search: a public bot's turn finds a Private Source attached to its assistant that the bot account cannot read
  otherwise; not an unattached Source; not an Auto Sync document the account cannot read; nothing when the assistant
  has no attachment; a Document Set's Sources count as attached.
- [ ] Chat: the code interpreter is not offered on a public bot's turn.
- [ ] `ChannelApiTest`, `OpenApiContractTest`.

## PR 2: web

- [ ] *Công khai* switch on the bot page. Switching on opens a dialog naming the bot's assistant and each group's own
  assistant with their Sources, Document Sets and files; confirming saves. Switching off saves at once.
- [ ] The assistant editor's warning beside its knowledge section while a public bot answers with the assistant,
  naming the bots, from the channel listing.
- [ ] i18n (vi, en), component tests, screenshots at desktop and phone width, light and dark.

## Docs

- [ ] Channel spec (a public bot), identity spec (what the bot account reads on a public bot), chat spec (the turn's
  restrictions), the channel and chat test matrices, the runbooks' bot pages, roadmap. Done in each PR for its part.

## Acceptance on staging

- [ ] The staging Zalo bot switched public; a Zalo account that is not linked asks it directly and in a group and is
  answered from the assistant's attachments only, with no links; a document outside them is not found.
- [ ] Switched back to private, the same account is told to link, and the earlier conversation is not continued.
- [ ] Needs the owner to send the messages from that account.
