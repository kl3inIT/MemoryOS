# 31. WhatsApp through a whatsmeow bridge in the channels process, and a lease for each bot

Date: 2026-10-19

## Status

Accepted on 2026-10-19 with the [MEM-283 design](../increments/active/mem-283-whatsapp-bot/design.md) and implemented
in the same change. Extends [ADR 0028](0028-chat-channels-in-their-own-process.md), whose single channels process it
lets become several.

## Context

The owner chose WhatsApp Web, a number linked as a companion device, over Meta's Cloud API, and no container of its
own for one channel. The JVM has no maintained WhatsApp Web library: Cobalt's current artifact is not on Maven
Central and had no commit for three months while whatsmeow had 68 protocol fixes, one of them on the send path where
Cobalt has an open issue. whatsmeow (Go) is what GoClaw runs in process and what GOWA and WAHA wrap. Hermes Agent,
an application whose language has no such library either, runs Baileys in a bridge process it starts itself. GOWA,
the ready gateway on whatsmeow, writes every message and its device registry to local SQLite, ends the whole process
when one session is opened elsewhere, and announces logins only on a WebSocket, which a read-only container and the
channel's retention do not allow.

A linked device is one session: two processes holding it replace each other's stream in a loop. Today every channels
process opens every bot, so the deployment could run only one, and a second Lark or Zalo holder would also clash.

## Decision

- **A bridge of our own on whatsmeow,** `memoryos-integration-whatsapp/bridge/`, a static Go binary the `channels`
  image carries and the WhatsApp adapter starts as a child process when the first bot opens. They speak one JSON object
  a line over the bridge's standard streams; it holds no port and writes nothing to disk; it exits when its stdin
  closes. whatsmeow is pinned in `go.mod` and bumped by Dependabot.
- **Sessions in PostgreSQL,** in the schema `whatsapp`, whatsmeow's own tables beside `channel_devices`, which names
  the device of each bot, so a session is found by its bot and never by its number, and a number links one bot.
- **A lease for each bot** in `channel_connection_state`, taken and renewed on the database's clock, closed by its
  holder before it can end, with an epoch on every write of its holder, and released on stop, so any number of channel
  processes share the bots, as Onyx's Slack pods share Tenants under Redis locks. A webhook request for a bot held
  elsewhere is passed to its holder.

## Consequences

- The `channels` image carries an 18 MB Go binary; CI builds and tests it in the integrations leg, and the image's
  `whatsapp-bridge` stage builds it.
- A WhatsApp number can be banned; the bot only replies, and the runbook holds the operating rules.
- whatsmeow keeps each device's keys unencrypted in its tables, as GoClaw does: a database dump can take over a number.
- Failover takes up to the lease (90 seconds) plus a pass when a holder dies, and none when it stops.
- A WhatsApp group is answered only once MEM-285's rule can tell that everyone in it belongs to the Tenant.
