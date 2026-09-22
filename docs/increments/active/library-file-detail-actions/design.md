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
question bar. Submitting it copies the file into an upload (`libraryUpload`, which is the same copy the
library picker and Projects already make), creates a conversation titled after the question, and navigates
to `/chat/{id}?ask=…&attach={fileId}`. `attach` is new; `ask` already existed for a question started from
an agent's detail view. The seeding effect that owns `ask` now attaches the file first and only then sends
the question, so the answer is given about the file and never about nothing. An empty question opens the
conversation with the file attached and sends nothing.

No chat surface is embedded in the library. The assistant-ui runtime is bound to the chat routes
(`ChatRuntimeProvider` resolves the visible thread from the path), so a second conversation surface on
`/library` would be a parallel implementation of the one Chat already owns.

**A crop is made from the bytes the preview already holds.** The image footer gains a crop toggle. In crop
mode the image is shown upright at 100% — a crop is drawn on the image as stored, not on a rotated or
zoomed view — and dragging selects a rectangle, reported in the image's own pixels. The selection is
encoded by a canvas in the browser (`renderCrop`), so nothing is uploaded until the owner keeps it:
`Tải ảnh đã cắt` writes it locally, `Lưu thành tệp mới` hands it to the caller. The library passes the
result to `useLibraryUploads`, so a crop arrives through the ordinary upload pipeline, with the upload
tray, the storage quota and the usual lifecycle. The new file is named `<name> (đã cắt).<ext>`, where the
extension is the type the canvas actually encoded (PNG unless the source was JPEG or WebP).

The crop is deliberately client-side: the server has no image editing route, and adding one for a
rectangle the browser can already cut would be a capability nothing else needs.

**The library pages like every other table.** `loadLibrary` takes the page size as an argument,
`TablePagination` carries the summary and the counter, and `PageSizeSelect` offers 12, 24, 50 and 100 —
100 is the server's own listing ceiling (`ChatPersonaService.page`). Changing the size restarts at the
first page, as every filter change does. The bar stays on screen whenever the library holds a file, so the
page size is reachable on a single-page library.

## Consequences

- `ChatFilePreviewModal` takes two optional handlers, `onAsk` and `onSaveImage`. Surfaces inside Chat
  (`chat-sources`, `chat-session-files`) pass neither: they are already a conversation, and a file there is
  already attached.
- A question asked from the library costs one copy, one conversation and one upload read. An upload is
  attached as itself; a generated file or image is copied first, exactly as attaching it in Chat does.
- The crop reads the whole image into the browser, which the preview already does to show it.
