# Plan

## Steps

1. **Page size** — `loadLibrary(filter, offset, signal, limit)` and `LIBRARY_PAGE_SIZES` (12, 24, 50, 100,
   the server's listing ceiling); the library renders `TablePagination` whenever it holds a file, with the
   summary, the page counter and `PageSizeSelect`. Changing the size restarts at the first page. Done.
2. **Crop helpers** — `library/image-crop.ts`: `rectBetween` (a drag in either direction, clamped to the
   image), `cropPixels`, `cropType`, `croppedFileName`, `renderCrop` (canvas). Done.
3. **Cropper** — `library/image-cropper.tsx`: drag over the image, everything outside the selection dimmed,
   the image's own pixel size reported on load. Done.
4. **Preview modal** — a crop toggle beside zoom and rotate; crop mode shows the image upright at 100%;
   `Tải ảnh đã cắt` writes the file locally and `Lưu thành tệp mới` calls `onSaveImage`. The library hands
   that to `useLibraryUploads`, so a crop is an ordinary upload with the tray and the quota. Done.
5. **Ask in Chat** — the modal's question bar calls `onAsk`; the library copies the file into an upload,
   creates a conversation and navigates to `/chat/{id}?ask=…&attach={fileId}`. Done.
6. **Seeding** — `attach` on the chat route; the effect that owned `ask` attaches the file, then sends the
   question. A file that never arrives leaves the question in the composer instead of asking about
   nothing. Done.

## Verification

| What | Where |
| --- | --- |
| A drag in either direction, clamped to the image; the crop in the image's pixels; the saved type and name | `library/image-crop.test.ts` |
| Crop mode selects a region, reports `640 × 360 px`, saves a `… (đã cắt).png` file, and reports a failed encode; only a download where nothing can be saved | `library/file-preview-crop.test.tsx` |
| The page size re-cuts the list from its first page, with the summary | `library/library-page.test.tsx` — "cuts the list into pages of the chosen size" |
| A question opens a conversation with the file attached, copying nothing for an upload | `chat/library/chat-library.test.tsx` — "asks Chat about the file being previewed" |
| The file is attached before the question is sent; nothing is sent when the file never arrives; a file with no question | `chat/session/chat-conversation-seed.test.tsx` |
| The page size control, the crop drawn on a real image, the crop saved into the library, and the navigation to `/chat/{id}?ask=…&attach=…` | Browser against `/library` on the throwaway preview API |

Not verified here: the question arriving in a live conversation, which needs the real API rather than the
throwaway preview server; the unit tests cover the attach-then-send order.

## Open

- Keyboard-only cropping: the selection is drawn with a pointer.
