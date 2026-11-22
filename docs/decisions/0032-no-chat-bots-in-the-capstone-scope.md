# 32. No chat bots: MemoryOS serves the capstone's scope

Date: 2026-11-22

## Status

Accepted by the owner on 2026-11-22 and implemented in step 1 of the
[capstone cleanup](../increments/active/capstone-cleanup/plan.md). Supersedes
[ADR 0028](0028-chat-channels-in-their-own-process.md) and [ADR 0031](0031-whatsapp-bridge-and-bot-leases.md).

## Context

ADR 0028 put the chat bots (Lark, then Zalo) in a `channel` capability and a `memoryos-channels` process; ADR 0031
added WhatsApp through a Go bridge and a lease for each bot so that several channel processes could share them. They
were built from 2026-10-13, after the SEP490 requirements froze on 2026-10-11.

MemoryOS now serves only the SEP490 capstone (customer project ended; defence on 2026-12-21). Its scope is Report 3,
FE-01 to FE-07, which names no chat bot, channel, Zalo or WhatsApp; the Report 4 diagrams already leave them out. The
bots kept a process, a Go binary, three platform integrations, twelve tables and five columns, an administration page and
an encryption key alive on staging for a feature the capstone does not assess, and every change to Chat had to keep
them working. Bot and personal-assistant work moves to the owner's Hypomnema platform, which already has Lark,
WhatsApp and Zalo bots.

## Decision

- **Removed.** The `memoryos-channels` deployable, the core capability `io.memoryos.channel` with its persistence,
  `memoryos-integration-zalo`, `memoryos-integration-whatsapp` with its bridge, and the Lark bot
  (`LarkChannelAdapter`, `LarkReplyCard`). The Lark connector, a Source, stays in `memoryos-integration-lark`.
- **Chat runs on the web only.** Chat keeps no binding of bots or group chats to assistants, no assistant for a
  member's direct messages to a bot, no public-bot reading mode (whole Sources, no code interpreter) and no MCP
  surface for bots; an MCP server is no longer opened to public bots. `CHANNELS_MANAGE` is gone from IAM, the
  `channel.*` actions from the audit taxonomy the application writes, and `ServiceAccounts` from IAM, since only bots
  created service accounts.
- **Data.** Migration V202 drops the bots' tables, the WhatsApp bridge's schema, the `chat_session` and
  `chat_preferences` channel columns, `mcp_server.public_bots`, the `CHANNELS_MANAGE` grants and the bots' retention
  task. It keeps what people may still read: the conversations the bots held (`chat_session.surface = 'CHANNEL'`)
  stay their owners' and in the administrator's chat history, the bots' service accounts stay as the actors those
  conversations and the audit log name, and audit events keep their recorded `channel.*` actions.
- **Deployment.** No release builds or deploys a channels image; `deploy.sh` still restores the channels process
  when it rolls back to a staging runtime from before this change, and takes down the one such a runtime left.

## Consequences

- The previous release cannot run on the new schema: rolling back past V202 is a database restore
  ([ADR 0018](0018-schema-changes-preserve-data.md)).
- Chat, IAM, MCP and retrieval lose the branches that served bots: one surface for a turn, one reading mode, no
  bot accounts to budget for beyond those that already exist.
- Whoever brings a bot back starts from a new design against the code as it is then; the removed code is on the tag
  `archive/pre-capstone-cleanup` and in the history.
