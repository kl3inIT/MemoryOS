# Chat limits belong to the person

## Why

Two Chat limits were built as Tenant administration in MEM-152 and MEM-153, and both were put in the wrong
place:

- **`/admin/file-storage`** let a model manager record a per-person storage limit and exceptions for
  individuals. The bytes it bounds live in one object store whose size is an operational fact of the
  deployment, not a per-Tenant product decision, and the page asked an administrator to type an actor id to
  raise one person's room — administration nobody wants to do. What a person genuinely needs is what ChatGPT
  shows them: how much of their own storage they have used, and where to go to free some.
- **`/admin/chat-retention`** let a model manager decide how long *everyone's* conversations survive. The
  thing it deletes is one person's own history. ChatGPT puts the same number in the person's own settings, and
  so does this change.

A third defect surfaced while checking the first two: an answer that generated an image kept a "deleted" card
only until the byte sweep ran, because the sweep deleted the artifact row. After that the image vanished from
the conversation with nothing said — the answer silently lost content it had produced.

## Decisions

1. **The storage maximum is deployment configuration.** `memoryos.chat.storage.library-bytes`
   (`MEMORYOS_CHAT_STORAGE_LIBRARY_BYTES`) bounds every person's library, defaulting to 512 MiB; `0` means no
   limit, which is how Chat behaved before a limit existed. `ChatStorageQuotaService` reads it instead of
   `chat_storage_quota`, and the table, its per-person exceptions, `chat_settings.storage_quota_bytes`, the
   `/api/chat/storage-quota` routes and the administration page are gone. A limit that can be raised for one
   person is a product feature nobody asked for; a deployment ceiling is what the object store actually has.
2. **The person sees their own storage, from either side.** One `StorageMeter` — used against the maximum,
   then one row per category, biggest first, because that is the one worth clearing — has two entrances:
   `/settings/storage` ("Bộ nhớ lưu trữ"), where a row is a link into `/library?category=…`, and a settings
   panel on the library page itself, where the same row narrows the list already on screen (`onCategory`).
   Both entrances hold the same things — the meter, the trash window and the retention section — so
   everything about *my* files and *my* history is in one place wherever the person looks; the settings page
   additionally offers the way into the library itself. Neither entrance offers to change the maximum, because
   nothing in the product can. Controls inside the library use the Radix select rather than the native one:
   a native popup is drawn by the operating system and looks foreign beside the library's own surfaces.
3. **Retention is the owner's setting.** `chat_preferences.retention_days` holds it, `/api/chat/retention`
   reads and writes it for the caller and nobody else, and the personal Chat settings page offers Never, 30,
   90, 180, 365 days or a typed number. The worker's `policies()` now returns one row per person, and
   `applyRetention` is scoped to `owner_actor_id`: my number bounds my history and no one else's. A Tenant
   that had recorded a policy hands that number to each of its members in the migration rather than losing it.
4. **The count before the save stays.** Deleting is not undoable, so `/api/chat/retention/preview` counts the
   caller's own conversations already past the number, and the confirm dialog says how many will go. This is
   the one thing the administration page got right.
5. **A purged artifact leaves a tombstone.** The cleanup sweep no longer deletes the artifact row: it sets
   `purged_at`, empties `stored_object_id`/`object_key` (and the preview columns), and keeps the name, the size
   and the message. `byMessages(includeDeleted = true)` therefore keeps returning the artifact, so the answer
   keeps its "Ảnh đã bị xoá" card for good, while the library listing, the trash, restore, re-purge and the
   claim all exclude tombstones — there are no bytes to serve, restore or charge for.
6. **No audit line for a personal choice.** `AuditAction.CHAT_SETTINGS_CHANGE` loses its `chatRetentionDays`
   detail. Audit evidence is for administration; a person changing their own retention is not that.

## Scope

In: the configuration property and the removal of the quota table, routes and page; the personal storage page;
per-person retention end to end (migration, persistence, service, API, worker, UI); the artifact tombstone; the
`Progress` value that never reached the primitive, found by testing the meter.

Out: quotas per Tenant in any form, Project or Agent storage, the upload size limit
(`memoryos.chat.files.max-size-bytes`, unchanged), and the conversation trash window
(`memoryos.chat.retention.deleted-after`, unchanged — that is a deployment window, not a person's choice).

## Module move (2026-09-25)

[ADR 0015 step 3](../../../decisions/0015-capability-module-map.md#step-3-what-library-holds) moved the code this increment names into the `library` module with its class names unchanged: `ChatStorageQuotaService` and `ChatStorageProperties` are `io.memoryos.library`, and the trash window is `LibraryTrashProperties` there. The configuration keys (`memoryos.chat.storage.library-bytes`, `memoryos.chat.retention.trash-after`) and the `CHAT_STORAGE_FULL` code are unchanged; Chat's `ChatRetentionProperties` keeps only the conversation windows.
