# 28. Chat channels run in their own process behind a `channel` capability

Date: 2026-10-15

## Status

Accepted with the [MEM-246 design](../increments/active/mem-246-lark-bot/design.md) on 2026-10-13; implemented in
PRs #631 to #656 and running on staging with the Lark bot.

## Context

A Tenant wants its members to ask MemoryOS from the messaging platform they already use, Lark first, under the same
access rules as the web. A bot holds a long-lived connection to the platform, receives bursts of messages it does
not schedule, and runs Chat turns with the model and tool stack. The references split: Onyx runs its Slack bot as a
process of its own that imports the chat pipeline; Dust runs a separate service that calls the conversation API
over HTTP with a key that may act as any member; WeKnora, RAGFlow and OpenClaw run the bot inside the agent's
process.

## Decision

- **A capability in `core`.** `io.memoryos.channel` is a `CLOSED` Spring Modulith module: the connections and their
  administration, the inbound rules, who a turn runs as, the conversation, and a supervisor that holds the
  connections. A platform is one `ChannelAdapter` keyed by `ChannelProvider` in a registry
  ([ADR 0019](0019-provider-families-use-adapters-behind-a-registry.md)); the Lark adapter lives in
  `memoryos-integration-lark` ([ADR 0027](0027-integrations-by-platform-and-memoryos-module-names.md)).
- **A deployable of its own.** `memoryos-channels` imports `ChannelRuntimeComponents` and runs the supervisor and the
  turns; it serves no HTTP API and runs no migration. A failing platform client or a burst of bot traffic cannot
  take the web application down, and each process has its own bound on turns.
- **Turns in process, through the API the web uses.** A question becomes a Chat turn through `ChatSessionService`
  in the channels process; no credential that may act as any member and no HTTP contract between processes is
  opened. The API and the channels process never call each other and share PostgreSQL and Redis: settings are rows
  the process reconciles, and its state is a row it writes.
- **Turns survive more than one process.** A turn's lease (three minutes, renewed every thirty seconds) replaces
  failing every running turn at start, and what must be shared between replicas, deduplication by message id and
  the order within a conversation, is in PostgreSQL.
- **Who a turn runs as.** A direct message runs as the member whose verified e-mail equals the one the platform
  holds; a group message, and a sender without an account, runs as the bot's own `SERVICE` account, which reads what
  every member may read.

## Consequences

- One more JVM with the chat and model stack on each host that runs bots, and a turn bound to size against the
  provider's limit together with the API's.
- A platform that gives no e-mail (Telegram, Zalo) needs an account binding a person makes from MemoryOS before a
  direct message can run as them; `channel_identities` is where it will be recorded.
- A second provider is a new adapter and enum constant; the capability, the process and the administration page
  stay.
