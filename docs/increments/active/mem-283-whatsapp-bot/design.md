# MEM-283 WhatsApp bot

A member asks the MemoryOS bot in WhatsApp, in a direct message or by naming it in a group, and is answered from what
they may read. It is the third provider of the `channel` capability, after Lark
([MEM-246](../mem-246-lark-bot/design.md)) and Zalo ([MEM-263](../mem-263-zalo-bot/design.md)); everything that
capability decides (who a turn runs as, linked accounts, conversations, order, the sender's rate, notices, the bounds,
a group's own assistant from MEM-273) holds here unless this page says otherwise.

## Goal and scope

Built and tested on staging: a WhatsApp number the Tenant dedicates to the bot, linked to MemoryOS as a companion
device ("Linked devices" in the WhatsApp app), the way WhatsApp Web is. Direct messages first, then groups once the
group rule below is in place.

Decided with the owner (2026-10-19):

- **WhatsApp Web, not the Cloud API.** The owner accepts the unofficial protocol and its terms risk. The earlier
  research on the official Business Platform (MEM-284: Meta's AI-provider clause 4.7, BSUID, the Cloud API's
  eight-person groups) no longer applies; MEM-284 is closed as superseded.
- **No container of its own.** The protocol runs as a child process inside the `memoryos-channels` container, not as
  a compose service.
- Staging only, as the other providers (`channels` does not run in production).

Not in scope: media (an image's caption is read as Lark and Zalo do; voice, stickers and files are `OTHER`), a
pairing code instead of the QR, calls, status updates, channels (newsletters), starting a conversation with anyone.

## How the references do it (source read 2026-10-19)

Clones: OpenClaw `e055783d`, GoClaw `8b1a476`, Hermes Agent `da04ec61`, whatsmeow `b2b1e71`, GOWA `v9.6.0`
(`62e319d5`), Cobalt `c7069802`. Onyx (`backend/onyx/onyxbot/` has only Slack and Discord) and Glean (Slack and
Teams) have no WhatsApp bot.

| | OpenClaw | GoClaw | Hermes Agent |
| --- | --- | --- | --- |
| Library | Baileys 7.0.0-rc14, in process | whatsmeow, in process | Baileys 7.0.0-rc13 in a Node bridge it starts with `Popen`, bound to 127.0.0.1 |
| Login | QR (`openclaw channels login`) | QR, a PNG pushed to the web wizard, 3-minute session (`qr_methods.go:22`) | QR in the terminal |
| Session | Files, atomic write and `.bak` | **PostgreSQL**, whatsmeow `sqlstore`, one device a channel instance (`whatsapp.go:116`) | Files |
| Logged out | Status and `doctor`; no retry | "Re-scan QR", not retried (`whatsapp.go:244-251`) | Bridge exits; operator re-pairs |
| Sender | Phone JID and LID | LID normalised to the phone JID through `SenderAlt` (`inbound.go:43-48`) | Phone and LID through `lid-mapping` |
| Own messages | `fromMe` never triggers | `IsFromMe` dropped (`inbound.go:28`) | Echoes dropped by recent ids |
| Group | Native mention, reply to the bot counts | Mention of the bot's JID or LID, fails closed when unknown (`inbound.go:346-372`) | `require_mention`; groups default closed |
| DM access | `dmPolicy` pairing (default) or allowlist | Pairing or allowlist, notice debounced to once a minute | Allowlist with pairing fallback |
| Send | 4,000 chunks, typing refreshed, read receipts on | 4,096 chunks, `composing` every 8 s then `paused` (`outbound.go:212-222`) | 4,096 chunks, 0.3 s between chunks, read receipts off |
| Socket | `syncFullHistory: false`, `markOnlineOnConnect: false` | library defaults | the same as OpenClaw |

None copies a protocol library into its repository: each depends on a maintained one and writes a thin adapter.
Hermes is the case nearest to MemoryOS, an application whose language has no good WhatsApp library, and it runs the
library in a bridge process of its own that it starts and talks to locally.

### The library

| | Cobalt (Java) | whatsmeow (Go) | Baileys (Node) |
| --- | --- | --- | --- |
| Last commit | 2026-07-15 | 2026-10-17 | 2026-08-04 |
| Protocol fixes since 2026-07-15 | none | 68 commits besides 17 protobuf regenerations, among them device-cache invalidation on DM send (`sendfb`, 2026-10-15), split socket frames, synchronous retry receipts, `participant_pn` for the alternate JID | — |
| Release | `cobalt-lib` 0.1.0 is not on Maven Central; pre-1.0, breaking changes expected | pinned by commit; used by GoClaw, GOWA, WAHA (GOWS), mautrix-whatsapp | 7.0.0-rc14 |
| Size | 4,916 Java files, about 1 M lines (479 k generated wire), 58 MB of native media libraries | | |
| Open issue on our path | #694: devices with an unconfirmed identity change are dropped from a send and never re-included | | |

Cobalt reads WhatsApp Web's `client_revision` at start (`WhatsAppWebClientInfo.java:20`), so it is not refused for an
old version; what it lacks is the flow of protocol fixes, and its open #694 is on the bot's send path. whatsmeow has
that flow and production users. **whatsmeow is the library.**

### Why not GOWA

GOWA (`go-whatsapp-web-multidevice`, MIT) wraps whatsmeow in a REST and webhook gateway. Read at `v9.6.0`, it does not
fit inside `memoryos-channels`:

- It always writes local state: a SQLite `devices` registry, a SQLite chat store holding every message
  (`event_message_handler.go:46`, `settings.go:79`, no switch) and history-sync dumps under `storages/`
  (`history_sync.go:28-36`). The `channels` container is `read_only` with a 128 MB `/tmp`, and keeping every message
  outside `channel_messages` would escape its 90-day retention.
- A session opened elsewhere (`StreamReplaced`) ends the whole process with `os.Exit(0)` (`event_handler.go:306-308`),
  taking every bot down.
- Login and logout are announced only on a WebSocket, not a webhook; webhooks are unordered, one goroutine each,
  at most once after five tries.
- It prints its whole configuration to stdout at start (`root.go:82`), downloads a web UI from GitHub at run time and
  serves an MCP endpoint, each to be switched off.

A bridge of our own on whatsmeow is smaller than the configuration and workarounds GOWA would need, as Hermes wrote
its own bridge on Baileys rather than adopt a gateway.

## Design

### The bridge

`memoryos-integration-whatsapp/bridge/` is a Go module (the latest Go, 1.27, and whatsmeow pinned in `go.mod`), one
binary, `memoryos-whatsapp-bridge`, built in its own Dockerfile stage (`CGO_ENABLED=0`, static) and copied into the
`channels` image only. It holds every WhatsApp bot of the process: one whatsmeow `Client` a bot, one process for all.

- **Talks over its standard streams, no port.** One JSON object a line: the JVM writes commands with a request id to
  its stdin and reads replies and events from its stdout; logs go to stderr as JSON lines (level, event, error type,
  never a message's text), which the adapter logs through SLF4J. No address, no password, no webhook signature: only
  the process that started it can speak to it. When stdin closes, the bridge disconnects every client and exits, so
  it never outlives the JVM.
- **Session in PostgreSQL.** whatsmeow's `sqlstore` on the MemoryOS database, schema `whatsapp`
  (`search_path=whatsapp`, pgx), as GoClaw keeps it; the bridge receives the address in its environment, never its
  arguments, and opens at most three connections. whatsmeow migrates its own tables (`whatsmeow_*`) in that schema;
  Flyway creates the schema (V192) and never touches its tables. Nothing is written to disk.
- **A session belongs to its bot,** not to its number. `whatsapp.channel_devices` (V192: bot, device JID, number;
  removed with the bot) names the device each bot linked, written when the bot's own number scans its code. Found by
  number, a session of a bot removed while no process held it would be reused by the next bot added for that number,
  in another Tenant too; found by bot, a new bot always links afresh, removing a bot logs out only its own device, and
  the bridge logs out every device no bot names a minute after it starts and every hour, sparing those it holds open
  (found by the review of the first commit).
- **Quiet device.** `SynchronousAck` on, history sync not downloaded (`ManualHistorySyncDownload`), no presence sent
  on connect, the device named "MemoryOS" in the phone's list of linked devices.
- **At least once.** With `SynchronousAck`, WhatsApp is acknowledged only after the handler returns; the handler
  writes the message event and waits up to 20 seconds for the JVM's `ack`, which the adapter sends once the capability
  claimed the message (as a webhook is answered after the claim). A bridge or JVM that dies first leaves the message
  unacknowledged, and WhatsApp delivers it again; the capability's deduplication by message id holds.
- **Commands.** `open {bot, phone}`, `close {bot}`, `logout {bot}`, `send {bot, chat, text, quote?}`,
  `typing {bot, chat, on}`, `read {bot, chat, sender, ids}`, `groups {bot}`, `group {bot, chat}`, `ack {event}`,
  `shutdown`.
- **Events.** `qr {bot, code, expiresAt}`, `pairingEnded {bot, reason}`, `paired {bot, jid, lid, name}`,
  `connected {bot, jid, lid, name}`, `disconnected {bot}`, `loggedOut {bot}`, `replaced {bot}`,
  `banned {bot, reason, expiresAt}`, `wrongNumber {bot, jid}`, `numberInUse {bot}`, `outdated {bot}`, `message {…}`,
  `joined {bot, chat, name}`.

### The adapter

`memoryos-integration-whatsapp` (`io.memoryos.integration.whatsapp`) holds `WhatsAppChannelAdapter`, provider
`WHATSAPP`, and the bridge client. The bridge starts with the first WhatsApp bot opened and is started again with a
back-off from one second to a minute when it exits; every transport it held reports `connected() == false`, and the
supervisor reopens them at its next pass. `memoryos.whatsapp.bridge-path` names the binary
(`/application/bin/memoryos-whatsapp-bridge` in the image); where it is missing, opening fails with
`CHANNEL_PROVIDER_UNAVAILABLE`.

- **Features.** No organisations (members link their account with a code, as for Zalo), no rewritten reply, 4,096
  characters (GoClaw and Hermes).
- **A bot** is the number given when it is added, in international form (`+84…`); its digits are the app id.
  `verify` checks the form only, since nothing can be asked before the number is linked. So the number is reserved
  within its Tenant only when the bot is added (a partial unique index), and linking decides it across Tenants: a
  number links one bot (`channel_devices.phone` unique, else `CHANNEL_WHATSAPP_NUMBER_IN_USE`), so no Tenant can hold
  another's number by adding it first. A WhatsApp bot has no secret: the session is the bridge's. `ChannelFeatures`
  gains `pairs` (the bot is linked by scanning, not by a secret); such a bot's secret columns are empty, which the
  database requires of it and refuses of any other (the shared cipher refuses an empty plaintext), and
  `replaceSecret` is refused.
- **Linking.** An enabled bot without a session opens into pairing: the bridge emits a QR code every 20 seconds or
  so for about two minutes, which the adapter hands to `ChannelReceiver.pairing`; the capability keeps the current
  code in `channel_pairings` (V192: bot, code, expiry), and the bot's page reads it every two seconds and draws it.
  When the QR session ends without a scan, the transport reports not connected and the next pass starts another.
  The number that scans must be the bot's: another is logged out at once and recorded as
  `CHANNEL_WHATSAPP_WRONG_NUMBER`, so a code seen by someone else cannot link their account into the Tenant.
- **Ending.** WhatsApp logging the device out (removed from the phone, or the number banned for good) is
  `CHANNEL_WHATSAPP_LOGGED_OUT`; a temporary ban is `CHANNEL_WHATSAPP_BANNED` with its end; the session opened by
  another process is `CHANNEL_WHATSAPP_REPLACED`. The transport reports each through `ChannelTransport.stopped()`,
  and each waits for the bot's next revision, as an unreachable webhook does, so no pass keeps relinking. "Liên kết
  lại" (`POST /api/channels/{id}/pairing`) moves the revision and the next pass shows a new QR. Removing the bot
  while a process holds it logs the device out (`ChannelAdapter.forget`); otherwise the bridge does within the hour.
- **A message.** The sender is known by their LID (`…@lid`), which stays when they change number or hide it behind a
  username; the phone JID is mapped to it through whatsmeow's LID store, and only a sender with no LID known is kept
  by phone. A direct chat is the sender; a group is its `…@g.us`. Messages the account sent itself, status updates,
  broadcasts and newsletters are dropped. Text is `conversation` or `extendedTextMessage.text`; an image's or video's
  caption is text followed by the note that the media was not read; anything else is `OTHER`. A reply names the
  quoted message (`contextInfo.stanzaId`).
- **Groups.** A group message addresses the bot when `contextInfo.mentionedJid` holds the bot's phone JID or LID, or
  it replies to one of the bot's messages; the `@<number>` of the bot's mention is removed. `groups(connection)` lists
  the joined groups with names (`GetJoinedGroups`), so a group's own assistant (MEM-273) works as for Lark.
- **The reply.** Marks the question read and sends `composing` at once and every 8 seconds, `paused` at the end.
  `finish` converts markdown to WhatsApp's own (`*bold*`, `_italic_`, `~strike~`, ```` ``` ```` blocks, lists, a link
  as its text and address, a table as `header: value` lines), cuts at 4,096 characters, quotes the question in the
  first piece and waits a random 0.5 to 1.5 seconds between pieces.

### Groups and people outside the Tenant (MEM-285)

A group turn runs as the bot's service account, which reads what every member may read. Zalo cannot list a group's
people, so MEM-285 found that a linked member can bring the bot into a group with outsiders. WhatsApp lists them:
`chat(connection, chatId)` answers the group's participants, and the capability answers a group only when every
participant other than the bot has linked an account in the bot's Tenant (OpenClaw's `groupPolicy` allowlist is the
manual form of the same rule). `ChannelChat` carries the participants' ids for a provider without organisations; a
group whose list cannot be read counts as external, as Lark's does. Until this is built, a WhatsApp group question is
refused with the existing external-group notice.

### One holder a bot, several processes

Today every `memoryos-channels` process opens every enabled bot, so the deployment runs one: two would hold two Lark
connections, set Zalo's webhook over each other, and for WhatsApp replace each other's stream (`StreamReplaced`)
in a loop, which is the kind of reconnect storm that draws a ban. Each bot is held under a lease instead, so any
number of processes share the bots and one takes over a bot whose holder died.

How the references and the platforms do it:

| | Unit | Timing | On losing it | On stopping |
| --- | --- | --- | --- | --- |
| Onyx Slack bot (`onyxbot/slack/listener.py`, `config.py:62-71`) | A Tenant, a Redis lock a pod takes without blocking | Heartbeat every 15 s, taken over after 60 s, acquisition every 60 s, at most 50 Tenants a pod | — | Released |
| Kubernetes leader election (controller-runtime `manager.go`) | One lease | Lease 15 s, renew deadline 10 s, retry 2 s | The holder stops acting once it cannot renew by the deadline, before the lease ends | `ReleaseOnCancel` hands over without waiting out the lease |
| MemoryOS's own claims (`cleanup_lease_until`, `minutes_lease_until`) | A row | The database's clock | — | — |

The rules, from those:

- **A lease a bot**, in `channel_connection_state` (V192: `holder`, `holder_address`, `lease_until`, `lease_epoch`),
  taken and renewed with one conditional `UPDATE … WHERE lease_until < now() OR holder = :me` on the database's
  clock, never a process's. The lease lasts 90 seconds; the supervisor renews the bots it holds every 10 seconds, on
  a thread apart from the 30-second pass, so a slow open never delays a renewal.
- **Stop before the lease ends.** A holder that has not renewed for 60 seconds (the database unreachable, a long
  pause) closes the bot's transport itself, so two processes never hold one bot; the next holder waits out the 90.
- **A new epoch each time a bot changes hands**, which the holder's writes of the bot's state carry, so a holder that
  lost the bot cannot overwrite its successor's state.
- **Release on stop.** A process that stops closes its bots and clears its leases, so another takes them at its next
  pass instead of 90 seconds later.
- **Stay put.** A holder keeps its bots; a process takes only bots nobody holds. There is no balancing: Onyx caps the
  Tenants a pod takes, which MemoryOS does not need at its size.
- **A webhook reaches the holder.** The provider pushes to any process behind `memoryos-channels:8082`; one that does
  not hold the bot passes the request to the holder's address (`holder_address`, its host name and port) and answers
  what the holder answered, since only the holder knows the webhook's secret of this opening (Zalo's is made at each
  opening). An unknown or dead holder is `503`, which the provider retries.
- **Abandoned claims.** A claimed message is taken for abandoned only when no live process holds its bot, not merely
  because it is older than this process.

A WhatsApp session is in PostgreSQL, so the next holder connects without a new QR.

### Not built, and why

- **A pairing code** (`PairPhone`): none of the three references uses it; the QR covers an administrator with the
  web open on a computer and the bot's phone in hand.
- **Encrypting the session at rest**: whatsmeow's store keeps the device's keys in its tables in plain form, as
  GoClaw's does. A database dump can take over the number; the schema lives with the rest of the Tenant's data and is
  covered by the same backups and access.

## Risks

- **The terms.** WhatsApp's terms forbid unofficial clients; a number can be banned, temporarily (`TemporaryBan`) or
  for good. Mitigations the references and WAHA's guide agree on: a dedicated number, only replies, typing before
  sending, pauses between pieces, no bulk or first contact, a filled profile. Spam reports from five to ten people can
  ban a number; the bot answers only linked members, so strangers get one linking notice at most once a minute.
- **Protocol changes.** whatsmeow is bumped by Dependabot (`gomod`) with the other dependencies; a break shows as
  bots not connecting, recorded by the supervisor.
