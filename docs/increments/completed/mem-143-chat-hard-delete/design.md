# MEM-143 — Hard deletion of deleted conversations

[MEM-143](https://linear.app/memory-os/issue/MEM-143) · depends on [MEM-142](../mem-142-file-library/design.md)

## Problem

Deleting a conversation is a soft deletion: the row keeps `deleted_at`, and the answers, the files `run_python` generated and the generated images stay in PostgreSQL and in object storage forever. MEM-142 withdrew those files from the library and gave artifacts a durable byte release, but nothing ever purges the conversation that was deleted. A deployment that promises deletion cannot keep that promise today.

## Reference

Onyx `f9e3de36c8`:

- `onyx/configs/chat_configs.py` reads one deployment switch, `HARD_DELETE_CHATS`, defaulting to off.
- `onyx/db/chat.py` `delete_chat_session` and `delete_all_chat_sessions_for_user` take `hard_delete=HARD_DELETE_CHATS`. With it off they set `deleted = True`; with it on they call `delete_messages_and_files_from_chat_session` and then `DELETE FROM chat_session`.
- `delete_messages_and_files_from_chat_session` deletes each message's stored file **except** one carrying `user_file_id` — "user files are managed by the user file lifecycle" — then deletes the `ChatMessage` rows and prunes orphaned search docs.
- The EE retention job (`maximum_chat_retention_days`) reuses the same function for sessions older than a cutoff. MemoryOS does not adopt a retention period here; the switch is what this increment delivers.

## Decisions

1. **One deployment switch, as Onyx.** `memoryos.chat.retention.hard-delete` (`MEMORYOS_CHAT_HARD_DELETE`), default false. With it off nothing changes and a deleted conversation stays exactly as it is today. There is no per-Tenant setting and no retention period: a retention window is a product decision this increment does not make.
2. **The purge runs in the worker, not in the request (departure from Onyx).** Onyx deletes stored files inside the delete request. MemoryOS cannot: artifact bytes are adopted object writes whose release needs the claim-fenced sweep MEM-142 added, and a request must not delete object-storage bytes. So deletion stays soft and immediate, and a `memoryos-chat-session-purge-v1` recurring task (one-minute fixed delay) purges what is already deleted. This also drains conversations that were deleted before the switch was turned on, which an in-request purge never would.
3. **What a purge removes,** for one claimed conversation, in one transaction:
   - every `chat_file_artifact` and `chat_image_artifact` of the session is marked `deleted_at`, so the MEM-142 sweep releases its bytes, its V73 preview and its object metadata;
   - `chat_command`, `chat_feedback`, `chat_sharing`, `chat_message` and finally `chat_session` rows are deleted in that order. The cascades would delete the same rows, but the order is explicit so a reference between two cascading tables cannot decide it.
   - `chat_user_file` is untouched, as Onyx skips `user_file_id`: an upload belongs to its owner and to the library, not to one conversation.
4. **A conversation with a running reply is not claimed.** Deleting a conversation cancels its reply, but the terminal write can still be in flight; the claim requires that no message of the session is `RUNNING`, so a purge never races a writer. The next run picks it up.
5. **No schema change.** The claim is the session row itself, taken with `FOR UPDATE SKIP LOCKED`; a failed purge rolls back and leaves the row soft-deleted for the next run. Bytes are released by the MEM-142 artifact sweep, which owns its own claim and lease.
6. **Nothing else observes the switch.** No API, no UI and no message changes: the delete confirmation already says a deletion cannot be undone. The Chat spec records that with the switch on, a deleted conversation and its generated files stop existing within minutes.

## Security

- The purge acts only on conversations already deleted by their owner; it never selects a live one.
- It carries no authorization of its own because it grants no access: it deletes rows and marks artifacts for release.
- Logs carry counts and identifiers only, never titles, message text or file names.

## Out of scope

Per-Tenant retention windows and a retention period (`maximum_chat_retention_days`), deleting uploads, administrative purge of another member's conversations, and restoring a deleted conversation.
