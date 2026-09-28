# File detail: ask Chat about a file, crop an image, page the library

## Problem

`/library` opens a file in the preview modal, and from there a person can only look at it, step to the
neighbouring file and download it. Three things were missing.

A question about a file had to be asked somewhere else: leave the library, start a conversation, find the
file again through the composer's library picker, then type the question. The file is already open on
screen; the detour is the whole cost.

An image could be zoomed and rotated but not cut. Keeping part of a screenshot or a diagram meant
downloading it, opening a local editor and uploading the result, which leaves the library holding a file
nobody can trace to the original.

The library paged in fixed slices of fifty with two buttons and no total, unlike every other paginated
table in the application, which carries a summary, a page counter and a rows-per-page control
(`TablePagination` with `PageSizeSelect`, as the Sources and Groups pages use).

## Decision

**A question is asked where the file is read, and answered by Chat.** The preview modal grows an optional
question composer, floating over the file with the Chat composer's own actions: a `+` that adds files from
the device or from the library, the model picker, dictation, and Send. Submitting copies the file into an
upload (`libraryUpload`, which is the same copy the library picker and Projects already make), uploads
anything added beside it, creates a conversation titled after the question, and navigates to
`/chat/{id}?ask=…&attach=…`. `attach` is new and carries the whole list; `ask` already existed for a
question started from an agent's detail view. The seeding effect that owns `ask` now attaches every file
first and only then sends the question, so the answer is given about them and never about nothing. An empty
question opens the conversation with the files attached and sends nothing.

The model picker writes the same per-actor preference Chat reads when a conversation opens
(`readChatModelPreference` / `writeChatModelPreference`), so a model chosen here is the model that answers.
Dictation is the Tenant's speech-to-text through `useDictationInput`, the hook Search also uses; Chat's own
composer keeps dictating through assistant-ui, which owns its draft.

No chat surface is embedded in the library. The assistant-ui runtime is bound to the chat routes
(`ChatRuntimeProvider` resolves the visible thread from the path), so a second conversation surface on
`/library` would be a parallel implementation of the one Chat already owns. The composer here holds a
question, not a thread.

**A crop is made from the bytes the preview already holds, and is used from then on.** The image footer
gains a crop toggle. In crop mode the image is shown upright at 100% — a crop is drawn on the image as
stored, not on a rotated or zoomed view — and dragging selects a rectangle, reported in the image's own
pixels. `Cắt` encodes the selection with a canvas in the browser (`renderCrop`) and the crop then replaces
what the viewer shows: it can be cropped again, downloaded, saved to the library as a new file, or asked
about, all without a round trip. `Lưu thành tệp mới` hands it to the caller, which passes it to
`useLibraryUploads`, so a crop arrives through the ordinary upload pipeline, with the upload tray, the
storage quota and the usual lifecycle. The new file is named `<name> (đã cắt).<ext>`, where the extension is
the type the canvas actually encoded (PNG unless the source was JPEG or WebP). `Về ảnh gốc` puts the
original back.

The crop is deliberately client-side: the server has no image editing route, and adding one for a
rectangle the browser can already cut would be a capability nothing else needs.

**The viewer turns and scales like an image viewer.** Rotation has a button per direction and accumulates
rather than folding at 360°, so a fourth turn to the right keeps turning right instead of animating back to
zero. The wheel over the picture scales it between 25% and 400%; the listener is registered non-passively so
the page behind the modal does not scroll while the image is being scaled.

**The library pages like every other table.** `loadLibrary` takes the page size as an argument,
`TablePagination` carries the summary and the counter, and `PageSizeSelect` offers 12, 24, 50 and 100 —
100 is the server's own listing ceiling (`ChatPersonaService.page`). Changing the size restarts at the
first page, as every filter change does. The bar stays on screen whenever the library holds a file, so the
page size is reachable on a single-page library.

## Consequences

- `ChatFilePreviewModal` takes two optional handlers, `onAsk` and `onSaveImage`. Surfaces inside Chat
  (`chat-sources`, `chat-session-files`) pass neither: they are already a conversation, and a file there is
  already attached.
- A question asked from the library costs one copy per file, one conversation and one upload read. An upload
  is attached as itself; a generated file, a generated image or a crop is uploaded first, exactly as
  attaching it in Chat does.
- The crop reads the whole image into the browser, which the preview already does to show it.

## Module move (2026-09-25)

[ADR 0015 step 3](../../../decisions/0015-capability-module-map.md#step-3-what-library-holds) moved the library listing into the `library` module. Its page ceiling is the library's own check (`Paging`), with the same bounds as `ChatPersonaService.page` (offset at most 10000, 1 to 100 rows), so the page-size control is unaffected.
