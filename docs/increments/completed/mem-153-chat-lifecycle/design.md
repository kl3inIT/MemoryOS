# MEM-153 — Conversation lifecycle: archive, branch, retention, temporary chats and export

[MEM-153](https://linear.app/memory-os/issue/MEM-153) · sub-issue of [MEM-142](../../completed/mem-142-file-library/design.md) · builds on [MEM-143](../../completed/mem-143-chat-hard-delete/design.md) and [MEM-152](../mem-152-file-library-v2/design.md)

## Problem

A conversation has two states today: on the sidebar, or deleted. Someone who wants an old conversation out of the way has to delete it; with `MEMORYOS_CHAT_HARD_DELETE` on, the MEM-143 purge then removes it almost at once, so a mistake is unrecoverable. There is no way to try a different direction from the middle of a conversation without disturbing it, no way to ask something that leaves no history, no way to take one's own conversations out of MemoryOS, and no way for an organization to bound how long conversations are kept.

## References

- **ChatGPT**: *Archive* hides a chat from the sidebar and keeps it (Settings › General › Archived chats); deleted chats disappear from the interface at once and are purged within 30 days; *Temporary chat* leaves no history, no Library entry and no memory; *Branch in new chat* on an answer; Data controls offer an export.
- **Onyx EE**: `maximum_chat_retention_days` deletes conversations whose last activity is older than the limit.
- **MemoryOS already has**: the `memoryos-chat-session-purge-v1` worker and the `hard-delete` switch (MEM-143), *Delete all chats* in Settings, versions inside one conversation when a question is edited, the file library and its trash (MEM-152), and the assistant-ui thread list, whose adapter already has `archive`/`unarchive` hooks that currently refuse.

## Scope and delivery

| Phase | Parts |
| --- | --- |
| 1 — Ordering conversations | 1 archive and archive-all, 2 branch into a new conversation |
| 2 — Deletion and retention | 3 the window before a hard delete, 4 temporary chats, 5 a Tenant retention policy |
| 3 — Taking data out | 6 export my conversations and files |

Each phase is its own pull request, and this document grows a section per phase as it starts.

## Phase 1 decisions — ordering conversations

1. **Archiving is a timestamp on the conversation, not a second list.** V100 adds `chat_session.archived_at` and a partial index for the ordinary listing. `GET /api/chat/sessions` keeps returning what is not archived; `?archived=true` returns the archive. `POST /api/chat/sessions/{id}/archive` and `…/unarchive` are owner-only and idempotent, so a repeated click is not an error.
2. **Archiving hides a conversation and changes nothing else.** Its files stay in the library, its share link keeps working, its Project keeps it, and history search still finds it — with `archived` on the row, so the result can say so. Sending a message into an archived conversation unarchives it, because a conversation someone is writing in is not archived; that happens in the same transaction that reserves the turn.
3. **Archive-all is a bounded command, like *Delete all chats*.** `POST /api/chat/sessions/archive-all` archives the owner's conversations in one statement and answers how many; it archives at most 1000 per call, which is the same bound the delete-all path uses for its batch.
4. **A branch is a copy of one path, made in one transaction.** `POST /api/chat/sessions/{id}/messages/{messageId}/branch` copies the selected path from the root up to and including that message into a new conversation of the caller, keeping each message's text, status, attachments, citations, activity and model record. The copy is one recursive statement that generates the new ids, so a branch of a hundred messages is one round trip and either happens or does not.
5. **A branch copies what an answer produced, bounded.** Generated files and images belong to the message that produced them, so a copied answer would lose its cards. Each artifact's bytes are therefore copied into a new object of the new message, through the same server-side write the library copy uses, while the whole branch stays under 50 artifacts and 30 MiB; past that the messages are copied without their artifacts and the response says so, because a branch must not become an unbounded byte copy. Uploads need no copying: an upload belongs to its owner, not to a conversation, so both conversations reference the same file.
6. **A branch refuses what it cannot copy faithfully.** A conversation that is not the caller's, a message that is not on its selected path, or a conversation with a reply still running answers a named refusal rather than copying a moving target. The new conversation records `branched_from_session_id`/`branched_from_message_id` so its header can link back, and it starts with the same assistant, Project and pinned reasoning level.

## Phase 2 decisions — deletion and retention

7. **A deleted conversation waits before it is purged.** `memoryos.chat.retention.deleted-after` (`MEMORYOS_CHAT_DELETED_AFTER`, default `30d`) bounds the purge's claim: only a conversation deleted longer ago than that is taken, and `0` reproduces the MEM-143 behaviour exactly. The window changes nothing a person sees — their conversation leaves the interface the moment they delete it — and the sweep's log line carries how many are still inside their window beside how many it purged. `hard-delete` remains the switch that decides whether anything is purged at all.
8. **A temporary conversation is a column, not a second kind of conversation.** V101 adds `chat_session.temporary`, and every listing that could reveal it excludes it: the sidebar, the archive, history search, the file library, Projects and sharing. It is the same conversation everywhere else, so no code path needs a second shape.
9. **A temporary conversation owns the uploads sent into it.** An upload normally belongs to its owner rather than to one conversation, which is why a conversation's deletion never touches it. That is exactly wrong for a conversation that promised to leave nothing, so `chat_user_file.temporary_session_id` records the conversation an upload was sent into: the library stops listing it, and the purge queues the ordinary DELETE file work that releases its bytes. Nothing else changes about uploads.
10. **Deleting itself needs no deployment switch.** `memoryos-chat-temporary-session-v1` soft-deletes a temporary conversation whose last message is older than `temporary-after` (default `24h`), and the purge then removes it and its uploads whether or not `hard-delete` is on and without waiting out the window of decision 7. A conversation that was promised to disappear cannot depend on an operator's setting.
11. **A Tenant retention policy is a setting on the Tenant, applied by a worker.** `chat_settings.chat_retention_days` (1–3650, absent means no policy) is administered with `MODELS_MANAGE`, like every other Tenant-wide Chat limit, and `memoryos-chat-retention-policy-v1` soft-deletes conversations whose last activity is older than it, 200 per Tenant per run, as their owner deleting them would. Nothing new deletes bytes: the existing purge and sweeps do the rest. Because the effect is not undoable, `GET /api/chat/settings/retention/preview?days=` answers how many conversations the number on screen would delete, and the admin page shows that before the policy can be saved.

## Phase 3 decisions — taking data out

12. **An export is the library archive's job with a different input.** `POST /api/chat/exports` records a request, `memoryos-chat-export-v1` packs it with a lease and an attempt count, the owner downloads `…/content` while `expires_at` (24 hours) holds, and the same sweep releases the bytes — the MEM-152 archive mechanics, reused rather than reimplemented. One export per person at a time, enforced by a partial unique index, because an export reads everything they own.
13. **An export holds transcripts first and files until the budget runs out.** The storage adapter writes one object of at most 32 MiB, so an export is bounded at 30 MiB: every conversation is written (at most 500 conversations, 500 messages each), then files are taken until the budget is spent. What was left out is named in the export's own index and in the response, because an export that silently omits data is worse than one that says what it omitted.
14. **Each conversation is written twice: as data and as a page.** The JSON keeps what the transcript recorded; the HTML is a page a browser opens on its own years later, with every value escaped, because a transcript is text people wrote. Deleted and temporary conversations are in no export — one is gone as far as its owner is concerned, the other promised to leave nothing.

## Security

Every owner route here is owner-only and Tenant-scoped, and none of them widens what a caller may read: a branch copies only messages the caller already reads and artifacts they already own, into a conversation they own; an export reads only what the caller's own listings already return, and only its owner may download it. Archiving changes no authorization at all. The retention policy is the one administrative surface, and it is bounded by `MODELS_MANAGE` on the caller's own Tenant. Logs and metrics carry counts and ids, never titles, message text or file names — the one exception is what an export reports to the person who asked for it, who already knows their own file names.

## Out of scope

Restoring a deleted conversation from the interface, an administrator reading or deleting someone else's conversations, and sharing an archive.
