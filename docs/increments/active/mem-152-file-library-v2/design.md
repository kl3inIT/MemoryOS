# MEM-152 — File library v2: reuse, direct upload, content search and bulk download

[MEM-152](https://linear.app/memory-os/issue/MEM-152) · sub-issue of [MEM-142](../../completed/mem-142-file-library/design.md) · builds on [MEM-144](../../completed/mem-144-session-files/design.md)

## Problem

MEM-142 made every file Chat holds for a person visible in `/library`, and MEM-144 put a conversation's own files behind its header. Both are read-only views. A file `run_python` wrote or an image the model generated cannot be attached to a later question, a Project or an assistant without downloading and uploading it again; the composer's "choose an existing file" lists uploads only; the library cannot take a file in without a conversation, finds files only by name, and downloads one file at a time.

## References

- **ChatGPT Library** (help article "Library", 2026): uploaded and created files are kept for reuse in later chats; Upload or drag-and-drop straight into the Library; browse, sort, search, select and download; search inside documents; the composer's `+` menu attaches recent files.
- **Mobbin, 2026-09-21** — the screens this increment takes its interaction patterns from:
  - ChatGPT Project *Sources* ([screen](https://mobbin.com/screens/67e7cde5-4772-43cf-9dd2-bdb51d1c4878)): one list row per file with a kind icon, name and "type · date", an *Add sources* row on top, sort and kind filters as compact menus on the right.
  - Grok *Personal files* ([screen](https://mobbin.com/screens/32a5408d-bd7a-4286-b9cf-ff85c2fc4030)) and Dropbox Dash *Add sources* ([screen](https://mobbin.com/screens/3d1e8345-1e76-4c37-9e24-af04d2b48f53)): a modal with a search field, an *Attach*/*Upload* action beside it, a flat row list, and a single primary *Done* in the footer.
  - Sana AI *Add content* ([screen](https://mobbin.com/screens/b21b83bd-22d0-4ecd-aef8-80c5a5e1421e)): filter chips under the search field and an "Added" state on rows already chosen.
  - Proton Drive and ElevenLabs upload trays ([Proton](https://mobbin.com/screens/218d53af-5262-4a58-ba33-150cf786437b), [ElevenLabs](https://mobbin.com/screens/dd75bcbc-6cee-4cd1-b9ca-46b600ef692f)): per-file rows with size, progress and a cancel control, grouped All / Active / Completed / Failed.
  - Otter *Trash* ([screen](https://mobbin.com/screens/d3c060a7-afb3-45fa-a53b-cf3430a33f02)): "deleted after 30 days" notice, *Empty trash now*, bulk *Restore* and *Delete forever*.

## Scope and delivery

The issue orders ten parts in four phases; each phase is its own pull request, and this document grows a section per phase as it starts.

| Phase | Parts |
| --- | --- |
| 1 — Reuse | 1 library picker in the composer (server-side copy), 2 actions in the conversation panel, 3 add to a Project |
| 2 — Bring in and find | 4 direct upload, 5 content search, 6 rename and favourite |
| 3 — Many files | 7 ZIP download, 8 better previews |
| 4 — Limits and safety | 9 storage usage and quota, 10 trash with restore (needs an ADR) |

## Phase 1 decisions — reuse

1. **An artifact becomes reusable by copying it into an upload, not by referencing it.** Every consumer that accepts files — turn admission, Projects, assistants, `edit_image`, file search — takes `chat_user_file` ids and runs through the upload's extraction and authorization. Teaching each of them a second kind of file id would widen every authorization check; a copy reuses them all unchanged. `POST /api/chat/library/{source}/{id}/copy` with `source` `GENERATED` or `IMAGE` returns the upload (`ChatFileResponse`), which is `PROCESSING` until the file worker extracts it like any other upload.
2. **The server copies the bytes; the browser never round-trips them.** `ObjectUploadService.write` stores bytes the server already holds as a *verified* upload of the `CHAT_FILE` purpose, following the browser path's lifecycle: the stored object and upload rows are reserved first, the bytes are written and inspected, and the caller adopts the upload in its own transaction. An upload never adopted — a crash between write and adoption, or a lost race — is reclaimed by the existing abandoned-upload cleanup, so no new reaper is needed. Storage IO runs outside any transaction; ownership and the artifact's existence are checked again under the owner lock before adoption.
3. **Asking twice returns the same copy.** V92 records `copied_from_source`/`copied_from_id` on the upload, with a unique index over the owner's *live* copies (status not `DELETING`/`DELETED`). A double click, a retried request or two tabs therefore yield one upload; the loser of a concurrent race discards its verified upload and returns the winner's. Deleting the copy frees the artifact to be copied again.
4. **The copy is independent of its artifact.** Deleting the artifact, or the MEM-143 purge removing its conversation, leaves the copy readable, because the copy owns its own stored object. Only an artifact the library lists can be copied: owned by the caller, in the active Tenant, not deleted, in a conversation that is not deleted. A foreign or absent artifact answers 404, as every owner-private file route does.
5. **Rows say which message they belong to.** The listing gains `messageId`: the answer that produced an artifact, and — when the list is narrowed to one conversation — the first message there that attached an upload. The conversation panel uses it to jump to the message. It is a hint for navigation, not an authorization.
6. **The composer picker is the library, not a second list.** "Choose an existing file" opens a dialog over `GET /api/chat/library` with the library's source and category filters and name search, following the Grok/Dash modal: search on top, flat rows with kind icon, name, source and size, multi-select up to the 20-file admission limit, and one primary *Attach* action. Picking a generated file or image calls the copy route and the dialog waits until the copy is `READY` before attaching it, so the composer only receives files it can send and its readiness rule stays unchanged. The popover keeps the three most recent uploads for the quick case and opens the library for everything else.
7. **The conversation panel and the library act on files, not only show them.** Each row gains *Attach to next message* (through the copy for artifacts), *Show in conversation* (scrolls to and highlights `messageId`; a message on another version is brought onto the selected path first, one version choice at a time from the top down, as the version arrows would — the history already holds the whole selected path, so nothing else needs loading) and *Add to Project*. Adding to a Project reuses `PUT /api/chat/projects/{id}` with the project's revision and file list; removing a file from a Project only removes the link. The panel refreshes its count when a turn finishes, so a file a run just produced appears without reloading.

## Security

The copy route reads only what the caller's library already lists and writes only an upload owned by the caller in the same Tenant, so it grants no new access; the copied upload is thereafter authorized exactly as an upload. Logs and metrics carry counts and sources, never file names, prompts or object keys.

## Out of scope

Folders, sharing a single file with someone else, referencing an artifact without copying it, and files of an assistant owned by someone else.
