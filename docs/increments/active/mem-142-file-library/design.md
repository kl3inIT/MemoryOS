# MEM-142 — My file library

[MEM-142](https://linear.app/memory-os/issue/MEM-142) · related [MEM-110](../../completed/mem-110-memoryos-interpreter/design.md), [MEM-111](../../completed/mem-111-generated-file-preview/design.md), [MEM-97](../../completed/mem-97-chat-image-generation/design.md)

## Problem

A person's files are scattered across conversations with nowhere to look at them, search them or clean them up.

- Uploads (`chat_user_file`, V37) have owner-scoped API routes, but the only surface is the composer.
- Files `run_python` generates (`chat_file_artifact`, V71–V73) and generated images (`chat_image_artifact`, V57/V62) are reachable only by opening the conversation that produced them. Neither has a listing or a delete route, so bytes accumulate with no way to release them.
- Deleting a conversation is a soft deletion, so its artifacts stay readable through their own routes and their bytes stay in object storage.

This increment delivers the owner-scoped listing, the artifact deletion path and the `/library` page. Hard deletion and retention of soft-deleted sessions stay in MEM-143; the per-session file panel stays in MEM-144.

## Reference

Onyx `f9e3de36c8`:

- `backend/onyx/server/features/projects/api.py` owns the user-file surface: `GET /projects/files/{project_id}`, `GET /projects/file/{file_id}`, `DELETE /projects/file/{file_id}` and `GET /projects/session/{chat_session_id}/files` (the MEM-144 shape).
- `delete_user_file` reads the file's `projects` and `assistants` relationships first and, when either is non-empty, returns `UserFileDeleteResult(has_associations=True, project_names, assistant_names)` **with 200** instead of deleting. Otherwise it sets `UserFileStatus.DELETING` and hands the bytes to a background Celery task (`DELETE_SINGLE_USER_FILE`), never to the request.
- `onyx/db/user_file.py` `fetch_user_files_with_access_relationships` and `fetch_user_project_ids_for_user_files` resolve those associations for a batch of files rather than one at a time.
- This snapshot has no library page: the removed `my-documents` surface survives only as a `searchParams` constant, and generated images live under `web/src/app/app/components/files/images`. The page below therefore follows the MemoryOS Search and Sources surfaces, not an Onyx screen.
- Onyx keeps `user_file` rows out of chat hard deletion (`user_file_id` rows are skipped), which MEM-143 inherits.

## Decisions

1. **One listing endpoint over three sources.** `GET /api/chat/library` returns the caller's own files in the active Tenant from `chat_user_file`, `chat_file_artifact` and `chat_image_artifact` as one paginated list. The repository builds a `UNION ALL` of three owner-filtered branches and wraps it for the name search, the source filter, the category filter and the sort; `COUNT(*) OVER ()` and `SUM(size_bytes) OVER ()` produce `totalCount` and `totalBytes` from the filtered set in the same statement, because window functions are evaluated before `LIMIT`. Paging is offset-based with `hasMore`, as `recent()` and `searchChatSessions` already page; it is live, not a snapshot.
2. **The artifact tables gain the columns a library needs (V89).**
   - `deleted_at`, `cleanup_token`, `cleanup_until` on both artifact tables carry the soft hide and the durable cleanup claim.
   - `owner_actor_id` and `session_id` are denormalized onto both artifact tables and backfilled through `chat_message` → `chat_session`. Without them every page of the union joins two tables per branch and sorts outside any index; with them each branch reads `(tenant_id, owner_actor_id, created_at DESC, id) WHERE deleted_at IS NULL`. Both are written at insert beside `message_id`, which remains the row's link to its answer.
   - `chat_image_artifact` gains `filename` and `size_bytes`; it had neither, so a generated image could not be named, searched or measured. `size_bytes` backfills from `stored_objects`.
3. **A generated image is named `image-<yyyyMMdd>-<HHmmss>-<8 hex of its id>.<ext>`** from its creation time and media type, assigned at insert and backfilled the same way. The name search matches `filename` for every source and additionally `revised_prompt` for images, because the prompt is what a person remembers about a generated image.
4. **Categories are derived, not stored.** `DOCUMENT`, `SPREADSHEET`, `IMAGE`, `PRESENTATION` and `OTHER` come from a `CASE` over media type and file extension in the listing query, so they cannot drift from the stored type and need no migration when a new type appears.
5. **Deleting an artifact hides it at once and releases its bytes durably.** `DELETE /api/chat/file-artifacts/{id}` and `DELETE /api/chat/image-artifacts/{id}` set `deleted_at` under the owner check, inside the request. A `memoryos-chat-artifact-cleanup-v1` recurring task (one-minute fixed delay, beside the other control-plane sweeps) then claims rows with `FOR UPDATE SKIP LOCKED` and a two-minute lease, exactly as `DefaultExtractionArtifactService.cleanup()` claims extraction artifacts. Per claimed row it marks the artifact object and the V73 preview object `DELETE_PENDING`, deletes both keys from storage outside the transaction, then releases ownership (`releaseAdopted`) and removes the metadata and the row in one token-fenced transaction. A failure leaves the lease to expire and the row to be claimed again. This sequence is required because artifact bytes are adopted object writes, which the generic reapers never select ([object storage contract](../../../specs/object-storage.md)), and `releaseAdopted` only drops the tracked write.
6. **A conversion racing a deletion cannot leak bytes.** `attachPreview` (MEM-111 pptx conversion) only records the PDF while `deleted_at IS NULL`; when the update matches no row the freshly staged object is discarded, so a conversion that finishes after the deletion leaves nothing adopted.
7. **Every read path filters deleted artifacts, except history.** `ownedArtifact`, `ownedChart`, the image `owned`/`inSession` lookups and the turn context all require `deleted_at IS NULL`; `inSession` and the turn context matter most, because they are what lets `edit_image` reuse an earlier image. Conversation history is the one exception: it returns the row with `deleted: true` and no size, so the answer still shows the file card in a deleted state instead of silently losing it. The `file_link` in the answer text then returns 404 as it does for any unavailable artifact.
8. **Uploads keep their existing lifecycle.** Only a `READY` upload is listed — the library previews and downloads a file, and those routes serve no other status, so one still processing or failed stays in the composer's own recent list — and it is listed regardless of any conversation, because an upload belongs to its owner and may be referenced by several conversations, by a Project or Agent, or by none. Deleting one keeps using `DELETE /api/chat/files/{id}` and its existing `chat_file_work` DELETE job. An upload therefore has no "open the original conversation" action: `chat_message.files` is a JSONB array with no index, and adding one for a secondary action is not justified here. Artifacts, which carry `session_id`, do have it.
9. **A file in use names what uses it (departure from Onyx).** `usedByWorkspace` becomes a batch lookup returning `{kind, id, name}` for every Project and Agent referencing a file, serving both the `usedBy` labels on the page and the conflict body. MemoryOS keeps the existing `409` rather than adopting Onyx's `200` with `has_associations`, because the request did not delete anything and the rest of this API reports refused commands with a status.
10. **Deletion is idempotent, and the page owns the refusals.** Deleting an artifact that is already deleted, or one the sweep has already removed, answers 204: an absent artifact is what the caller asked for, and a swept row cannot be told from an id that never existed. A page past the end of a filter still reports the filter's totals through a second aggregate over the same filtered rows, because the window totals ride on returned rows and an empty page carries none. A selection belongs to the page it was made on and is dropped when the page or filter changes, so a bulk delete can never act on rows that are no longer shown.

11. **Multi-select deletion is client-side.** The page deletes selected files one request at a time and reports per-file outcomes; no bulk endpoint is added. A partially refused selection is the normal case (a file attached to an Agent), and one failing item must not decide the fate of the others.
12. **The page is `/library`,** reached from a sidebar item beside Search documents and Agents. It offers a table and a grid (thumbnails for images), the source/category filters, name search, sort by date or size, Today/Yesterday/Earlier grouping and the total size of the current filter. A row previews through the MEM-111 `ChatFilePreviewModal`, whose `PreviewTarget` gains an image-artifact source; it also downloads, opens the originating conversation when there is one, and deletes with confirmation.
13. **Sharing is unchanged.** `GET /api/chat/shared/{id}/messages` already returns messages without image or generated-file references, and artifact bytes are owner-only, so a deleted file is gone from a shared transcript as well. This increment adds the test that keeps it that way rather than new behavior.

## Security

- Every listing branch and every delete is bound to the caller's Actor and active Tenant membership; no route accepts an owner parameter.
- The listing never returns a file of a soft-deleted conversation, so deleting a conversation still withdraws its artifacts.
- Deletion is irreversible by design and offers no restore; the confirmation says so.
- Logs and metrics carry counts, categories and sources, never file names, prompts or object keys ([observability conventions](../../../guidelines/observability.md)).

## Out of scope

Artifact versions and editing, folders, per-file sharing, storage quotas (MEM-123), knowledge spaces (MEM-131), document sets (MEM-138), hard deletion and retention of soft-deleted conversations (MEM-143), the per-session file panel (MEM-144).
