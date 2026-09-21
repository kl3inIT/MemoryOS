# MEM-143 plan — hard deletion of deleted conversations

- [x] **Switch.** `ChatRetentionProperties` bound to `memoryos.chat.retention` with `hard-delete` (default false) and the `MEMORYOS_CHAT_HARD_DELETE` environment binding in the worker configuration.
- [x] **Purge.** `JdbcChatSessionPurgeRepository` claims soft-deleted sessions with `FOR UPDATE SKIP LOCKED`, skipping any with a `RUNNING` message; marks their artifacts `deleted_at`; deletes `chat_command`, `chat_feedback`, `chat_sharing`, `chat_message`, `chat_session`. `ChatSessionPurgeService` runs a bounded batch per tick and does nothing while the switch is off.
- [x] **Schedule.** `memoryos-chat-session-purge-v1` on a one-minute fixed delay in `ControlPlaneConfiguration`; the service and repository imported into the worker application.
- [x] **Tests.** `ChatSessionPurgeIntegrationTest`: A purge releases the conversation's artifacts through the MEM-142 sweep, removes its command/feedback/sharing/message/session rows, keeps the owner's upload, skips a live conversation, skips one whose reply is still `RUNNING`, and does nothing at all while the switch is off.
- [x] **Docs.** Chat spec retention paragraph, verification matrix rows, `AGENTS.md` active increment entry.
- [ ] **Evidence.** `ChatSessionPurgeIntegrationTest` and `ControlPlaneIntegrationTest` pass locally (2026-09-20); the full `:core:test`/`:worker:test` runs and staging verification that a deleted conversation and its generated files disappear within minutes with the switch on.
