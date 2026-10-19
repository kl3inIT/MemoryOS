# Implementation plan

Design: [design.md](design.md). Two pull requests: the backend (steps 1 to 4) and the administration (step 5); the
group rule (step 6) rides with the second. Each is accepted when CI is green and staging deployed it.

## 1. The bridge

- [ ] `memoryos-integration-whatsapp/bridge/`: Go module on the latest Go and whatsmeow; the line protocol (commands,
  replies, events), one client a bot over `sqlstore` in schema `whatsapp`, `SynchronousAck` with the JVM's ack,
  pairing by QR with the number check, the events of the design, sender by LID, mention and reply detection, text
  and caption extraction, send with quote, typing, read, groups; exit when stdin closes.
- [ ] Go tests: protocol round trip, mention and reply detection, sender canonicalisation, text extraction.
- [ ] Dockerfile stage building it static; copied into the `channels` target only; CI job `go vet` and `go test`;
  Dependabot `gomod`.
- [ ] Probe on zm: the binary in the `channels` image against the staging database, schema `whatsapp`, a real
  number linked by QR, a direct text both ways, restart keeps the session, the joined groups listed.

## 2. The port

- [ ] `ChannelProvider.WHATSAPP`; `ChannelFeatures.pairs`; the empty secret for a pairing provider and
  `replaceSecret` refused; `ChannelReceiver.pairing`; the failure codes `CHANNEL_WHATSAPP_LOGGED_OUT`,
  `CHANNEL_WHATSAPP_BANNED`, `CHANNEL_WHATSAPP_REPLACED`, `CHANNEL_WHATSAPP_WRONG_NUMBER` waiting for the next
  revision.
- [ ] Migration V192: the provider check with `WHATSAPP` and its app id (`^[0-9]{8,15}$`), schema `whatsapp`,
  `channel_pairings`.
- [ ] The lease a bot: V192 columns, take and renew on the database's clock every 10 s, stop at 60 s without a
  renewal, a new epoch at each change of hands guarding the state writes, release on stop, abandoned claims only
  for bots no live process holds; the webhook passed to the holder's address, `503` when it is unknown or dead.
- [ ] Tests: `PostgresChannelConnectionTest` (checks, the empty secret, two holders racing for a lease, an expired
  one taken over, a stale epoch's write refused), `ChannelSupervisorTest` (pairing kept and cleared, the waiting
  codes, a lost renewal closing the transport, release on stop), `ChannelWebhookControllerTest` (passed to the
  holder).

## 3. The adapter

- [ ] `memoryos-integration-whatsapp`: build file with classpath isolation, autoconfiguration, properties
  (`memoryos.whatsapp.bridge-path`), the bridge client (start, back-off, stderr to SLF4J, request ids, ack), the
  adapter and its transport, `WhatsAppText`, the reply; dependency rule test.
- [ ] `WhatsAppChannelAdapterTest` against a stand-in bridge run as a real child process (a single-file Java source
  speaking the line protocol); `WhatsAppTextTest`.
- [ ] `memoryos-channels` and `memoryos-api` take it at run time; `ChannelsApplicationTest` finds the adapter.

## 4. Staging, direct messages

- [ ] Deploy; link the owner's number; a linked member asks in a direct message; an unlinked one gets the linking
  notice; restart `channels` and the session stays; log out from the phone and the bot shows
  `CHANNEL_WHATSAPP_LOGGED_OUT`. Record what the real number showed in the design.

## 5. The administration

- [ ] `POST /api/channels/whatsapp` (name, phone, language), `GET /api/channels/{id}/pairing`,
  `POST /api/channels/{id}/pairing` (link again); contract records; `openapi.yml` and the web client.
- [ ] Web: WhatsApp in the add dialog (number), its mark, the QR on the bot's page read every two seconds, the
  states and "Liên kết lại", every place that branches on the provider; vi and en copy; Playwright spec.

## 6. Groups (MEM-285)

- [ ] `ChannelChat` carries the participants for a provider without organisations; the capability answers a group
  only when every participant has linked an account in the Tenant; WhatsApp's `chat` reads the participants
  (cached a minute); `ChannelInboundServiceTest` proves a linked member cannot bring the bot into a group with an
  outsider. Close MEM-285 for WhatsApp; Zalo keeps its open question.
- [ ] Staging: a group of linked members gets answers when the bot is named; a group with an outsider is refused;
  a group's own assistant.

## 7. Documents

- [ ] `docs/specs/channel.md` (the WhatsApp adapter, the bridge, linking), `docs/tests/channel.md`,
  `ARCHITECTURE.md` (module and process), a runbook `docs/runbooks/whatsapp-bot.md` (dedicated number, linking, a
  ban, bumping whatsmeow), the roadmap; ADR for the bridge process inside `channels`.
