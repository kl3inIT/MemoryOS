# MEM-298 plan

Two pull requests after this design, each merged when CI is green on its head: the backend with its API, then the
bot page and the editor's warning. Acceptance on staging follows the second.

## PR 1: core and API

- [x] Migration V194: `channel_connections.public_access` and Chat's `chat_session.channel_public`, false everywhere.
- [x] `ChannelConnection.publicAccess`; `ChannelConnectionService.setPublic`: `CHANNELS_MANAGE`, the exclusive lock, the
  revision (`CHANNEL_CHANGED`), edit rights on the bot's and each group's assistant when making it public, audited
  `public` / `private`, the revision moved.
- [x] `ChannelInboundService`: a public bot skips linking and the sender and group refusals; the per-sender rate,
  empty, text-only and the guardrails stay; the turn runs as the bot, labelled with the asker's name, with no closing
  line and citations without links; a group is still recorded.
- [x] Conversations: `createInChannel` records the mode, `ChannelTurns.continues` continues one only in the bot's mode
  now, so switching starts over without dropping rows, and a reply to an older answer starts over too.
- [x] Chat: `JdbcChatRepository.persona` gives a public bot's conversation `ChatTurnOptions.forPublicBot`: the
  assistant's direct and Document Set Sources whoever may use the set, restricted even when empty, whole, no code
  interpreter. `SearchTool.withWholeSources` asks `SourceSearchService.attachedScope`.
- [x] Connector and retrieval: `attachedScope` (Public and Private Sources whole, Auto Sync ones the account may read),
  `SourceSearchScope.wholeSources`, `readableSourceMetadata` for the recheck, and the index's access filter admitting
  an origin in a whole Source.
- [x] The assistant's files granted to the bot's account through a conversation it holds with the assistant.
- [x] API: `PUT /api/channels/{channelId}/public` (`UpdateChannelPublicRequest{publicAccess}`), `publicAccess` on
  `ChannelResponse` and `AssistantChannelResponse`; `openapi.yml`, the contract test, the Hey API client and the web
  fixtures.
- [x] Tests: `ChannelInboundServiceTest` (2), `ChannelTurnsTest`, `PostgresChannelConnectionTest`,
  `SourceSyncAccessTest`, `OpenSearchRetrievalIntegrationTest`, `ChatPersistenceIntegrationTest` (2), `ChannelApiTest`.
- [x] Docs: channel, chat and identity specs; channel, chat and search test matrices.

## PR 2: web

- [ ] *Công khai* switch on the bot page. Switching on opens a dialog naming the bot's assistant and each group's own
  assistant with their Sources, Document Sets and files; confirming saves. Switching off saves at once.
- [ ] The assistant editor's warning beside its knowledge section while a public bot answers with the assistant,
  naming the bots, from the channel listing.
- [ ] i18n (vi, en), component tests, screenshots at desktop and phone width, light and dark.

## Docs

- [ ] PR 2: the bot page and the editor in the channel and chat specs; the runbooks' bot pages; roadmap.

## Acceptance on staging

- [ ] The staging Zalo bot switched public; a Zalo account that is not linked asks it directly and in a group and is
  answered from the assistant's attachments only, with no links; a document outside them is not found.
- [ ] Switched back to private, the same account is told to link, and the earlier conversation is not continued.
- [ ] Needs the owner to send the messages from that account.
