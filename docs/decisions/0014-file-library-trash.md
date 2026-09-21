# 14. A deleted library file waits in a trash before its bytes are released

Date: 2026-09-21

## Status

Accepted; implemented in MEM-152 phase 4.

## Context

[MEM-142](../increments/completed/mem-142-file-library/design.md) made deletion in the file library final: the row was hidden at once and the bytes were released by the next sweep, and the page said so ("cannot be restored"). That was the right first step — it kept one deletion path, with no second state to authorize — but it makes an ordinary mistake unrecoverable: a person clears a selection, or deletes the wrong row of a list they were sorting, and the file, its extracted text and its answer card are gone.

Every reference product keeps a window: ChatGPT deletes a conversation from the interface at once and purges it within 30 days, Google Drive and Proton Drive keep a trash, Otter shows "deleted after 30 days" with *Restore*, *Delete forever* and *Empty trash now*.

The constraint that shaped the decision is that MemoryOS never deletes bytes inside a request. An upload's bytes are released by the `chat_file_work` DELETE step, and an artifact's by the `memoryos-chat-artifact-cleanup-v1` sweep, both claim-fenced and retried. Whatever a trash does, it must not move byte deletion into the request that asks for it.

## Decision

Deleting a library file records **when its bytes may be released** instead of releasing them.

- Deletion behaves as before for everything a person sees: the file leaves every listing, preview, download, turn context and search at once, and history keeps its card as a tombstone.
- The row gains `purge_after` (uploads also gain `deleted_at`, which they had only implicitly through `status`). `memoryos.chat.retention.trash-after` (`MEMORYOS_CHAT_TRASH_AFTER`, default 30 days) sets the window; `0` reproduces the previous behaviour exactly, so a deployment may keep it.
- Nothing new deletes bytes. For an artifact, the existing sweep simply refuses to claim a row whose window has not passed. For an upload, `memoryos-chat-library-trash-v1` queues the existing DELETE work once the window has passed. Both paths keep their claims, leases and retries.
- `GET /api/chat/library?status=TRASH` lists what the owner deleted, with `deletedAt` and `purgeAfter`. `POST …/{source}/{id}/restore` takes a file back, `POST …/{source}/{id}/purge` ends its window now, and `POST /api/chat/library/trash/empty` ends every window the owner has.
- Restoring is only ever possible while the bytes are still there: an upload cannot be restored once its DELETE work is queued, and an artifact cannot be restored once the sweep has claimed it. Restoring an upload needs no repair, because its document and extracted text are removed by that same work, which had not run.

This supersedes the MEM-142 rule that deletion in the library is final. It does not change conversation deletion ([MEM-143](../increments/completed/mem-143-chat-hard-delete/design.md)), where the switch and the purge own the lifecycle.

## Consequences

- A mistake is recoverable for a month, and the storage a deleted file occupies is released a month later than before — which is why the window is configurable and why the quota (MEM-152 phase 4) counts only files the library lists, not what waits in the trash.
- Two states now exist for a deleted file (waiting, releasing), and every path that could resurrect a file has to check which one it is in; the tests cover restoring after the release is queued, and another member attempting either command.
- A deployment that must delete immediately sets `MEMORYOS_CHAT_TRASH_AFTER=0` and keeps exactly the MEM-142 behaviour, including the wording of the confirmation, which follows the configured window.
