# Document viewer

## Problem

The document preview dialog shows extracted passages as the only evidence for every non-PDF document.
A DOCX citation opens as flattened extraction text ("Đoạn trích"), a Markdown source opens as raw text
with a highlight band, and a PDF table opens as rows of disconnected cell text. Readers need the stored
original from object storage — the file as it actually looks — not the extraction by-product. Only PDFs
with recorded page provenance get the original today, through the "Trang PDF" tab.

Three further problems sit behind that one, and a viewer that only serves bytes leaves all three:

- **The citation disappears outside PDF.** Provenance records `page_no`/`bbox` only on the Docling layout
  route and `sheetName` for spreadsheets, so the yellow highlight in `DocumentPdfView` has nothing to draw
  from in a DOCX, a Markdown file or a workbook. An original rendered without its citation is a step back
  from the passages view, which at least bands the cited block.
- **The app already has a multi-format viewer, in the wrong place.**
  `web/src/features/library/file-preview-modal.tsx` (787 lines) and `file-preview.ts` render images,
  PDF, CSV, XLSX, DOCX, code, text and Markdown for owner-private chat files, with an Onyx-derived kind
  classifier, an RFC 4180 CSV reader and `sanitizeDocxHtml`. Writing a second set of viewers for Documents
  would violate [component and library reuse](../../../conventions.md#component-and-library-reuse) and leave two
  implementations to drift apart.
- **The dialog is not built for reading.** It is fixed at 56rem × 48rem whatever the file, has no download
  action, no toolbar outside the PDF view, one loading line instead of a skeleton, one red card for every
  failure, and no page navigation beyond "Về đoạn trích dẫn".

## Decision

Serve the stored original for every readable Document, render it through one shared viewer that Chat and
Search both use, and keep the citation visible in the original — highlighted where the text can be located,
and explicitly unlocated where it cannot.

### Backend: one original route, any media type

`DocumentOriginalService` already resolves originals of any media type (`sources.originals`, used by
`citationOriginal` for run_python staging) and enforces the same authority as the passage readers,
rechecked after the object is opened. The two HTTP routes — `GET /api/search/documents/{id}/original`
(SEARCH_READ) and `GET /api/chat/documents/{id}/original` (Chat citation authority) — currently call the
PDF-only path. They now serve any media type:

- `Content-Type` is the object's declared media type, not `application/octet-stream`, so the browser and
  pdf.js can render it.
- `Content-Disposition: inline; filename="…"` carries the stored filename.
- `X-Content-Type-Options: nosniff` on every response.
- Types that can execute script in the app origin — `text/html`, `image/svg+xml`, `application/xhtml+xml`
  — are served as `attachment` instead of `inline`. The viewer never requests them for display; the
  header is the server-side guarantee.
- Byte ranges stay supported for every type (pdf.js needs them; other viewers fetch whole).
- The 64 MiB ceiling and the open-then-recheck authority sequence are unchanged.

`DocumentOriginalService` gains `searchOriginal(actor, id, generation, range)` (SEARCH_READ, any type)
next to the existing `citationOriginal`; the PDF-only `searchPdf`/`citationPdf` keep their magic-byte
check for the pdf.js path. The route picks the variant by the Document's declared media type: PDF keeps
the magic-byte check, everything else goes through the general path.

### Backend: parsed workbooks for Documents

A workbook must not reach the browser as bytes — the viewer would need a client-side XLSX parser the app
does not ship. `SpreadsheetPreview` (POI streaming reader, cached formula values, never evaluated) already
parses a workbook into per-sheet CSV for chat files, but it sits in `io.memoryos.chat.interpreter`. It moves
to `io.memoryos.document` as capability-neutral parsing, and Chat keeps calling it from there.

Two routes are added beside the original routes, returning the same sheet shape the chat routes already
return: `GET /api/search/documents/{id}/spreadsheet` (SEARCH_READ) and
`GET /api/chat/documents/{id}/spreadsheet` (Chat citation authority). Both resolve the original through
`DocumentOriginalService` under the same authority and recheck, so no new permission surface appears.

### Frontend: one shared preview kit

The Chat viewers move to `web/src/features/preview/` as a pure extraction — behaviour unchanged, the
existing `chat-file-preview.test.ts` (now `preview/preview-kind.test.ts`) and `library/file-preview-gallery.test.tsx` staying green as the safety
net:

| Module | Moved from | Holds |
| --- | --- | --- |
| `preview-kind.ts` | `library/file-preview.ts` | `previewKind`, `codeLanguage`, `parseCsv`, `sanitizeDocxHtml`, size and byte ceilings |
| `pdf-view.tsx` | `search/document-pdf-view.tsx` (removed by the move) | pdf.js reader, range loading, bbox highlight |
| `docx-view.tsx`, `sheet-view.tsx`, `csv-view.tsx`, `text-view.tsx`, `image-view.tsx`, `download-view.tsx` | `library/file-preview-modal.tsx` | the existing per-kind renderers |
| `preview-surface.tsx` | new | the canvas: sunken backdrop, white sheet, elevation |
| `preview-toolbar.tsx` | new | the floating toolbar shared by every kind |
| `file-preview.tsx`, `lazy-pdf-view.tsx` | phase 5 (2026-09-26) | the one component Library, Search and Chat render a file through: view chosen by kind, citation highlight and previous/next, pdf.js loaded lazily; `OriginalView` is its loader for Search and Chat. `chat-file-reader` stays separate: it pages extracted text by character offset |
| `preview-highlight.ts` | new | locating a cited passage inside a rendered view |

`ChatFilePreviewModal` keeps its own shell — it owns sibling navigation and the three Onyx window sizes —
and renders `features/preview` viewers inside it. The document dialog gets the new reading shell below.
Neither shell owns a viewer.

### Frontend: citation highlighting per format

The rule that governs every path: **an unlocated citation is never drawn**. A highlight in the wrong place
tells the reader a passage came from text that is not its source, which is worse than no highlight.
`locateCitation` therefore returns `exact`, `approximate` or `none`, and `none` renders no mark and a stated
reason in the citation rail.

| Format | How the citation is located |
| --- | --- |
| PDF | Unchanged: `page_no` + `bbox` from provenance, drawn as today's `--pdf-highlight` overlay with `mix-blend-multiply` |
| DOCX, Markdown, TXT, CSV | Text matching in the rendered DOM (below) |
| XLSX | The sheet and row recorded in provenance are selected and that row is banded. Matching the text was tried and abandoned: extraction and the preview format the same cell differently (`$   1,618.50` against `1618.5`), so no normalization can join them, while the recorded row is exact |
| Images, PDFs without OCR text | Not located, and said so — there is no text layer to anchor to |

Text matching, in `preview-highlight.ts`:

1. Flatten the rendered container's text nodes into one string, keeping an index map back to
   (node, offset).
2. Normalise both that string and the passage: Unicode NFC, collapsed whitespace, soft hyphens removed,
   case folded.
3. Try an exact normalised substring match, and accept it only when it is the document's **only**
   occurrence. A repeated line — a table header on every page, a boilerplate footer — says nothing about
   which one was cited.
4. On a miss, anchor-match: the passage's first and last eight words, taking the span between them, and
   accept only when its length is within 40% of the expected length. Fewer than three anchor words on
   either end is refused outright: two words are too common to identify a place. Otherwise `none`.
5. Paint through the CSS Custom Highlight API — `CSS.highlights.set(…)` with a `::highlight()` rule using
   `--pdf-highlight`. Nothing is inserted into the DOM, so `docx-preview`'s layout and
   `sanitizeDocxHtml`'s guarantees are untouched. A browser without the API gets scroll-to-position and the
   `none` notice, never a DOM-rewriting fallback.

Extraction text and rendered text legitimately differ — a table row is flattened into a sentence, a
hyphenated line break is joined — so `approximate` is the expected outcome for many DOCX citations and is
shown as such.

### Frontend: two surfaces, not one

`EvidenceViewSwitch` serves two places, not just the dialog: `ChatSourcePanel` renders it inline in the
narrow Chat source panel, and shares the chosen tab with its expanded dialog through `view`/`onViewChange`.
A three-column reading layout does not fit that panel, so the two surfaces diverge deliberately:

- **The inline source panel keeps the two tabs.** "Đoạn trích" and "Tệp gốc" (replacing "Trang PDF"), with
  the original opening first only for the formats where it carries more than the passage does — PDF, DOCX,
  XLSX and images. For Markdown and plain text the passage view stays first, because the original is the
  same text without the citation band. The panel gets the shared viewers and the highlighting; it does not
  get the rails.
- **The dialog is the reading surface** described below, for the Search page and for the panel's expanded
  view.

Because the dialog shows the original and the passages at once, it no longer has a tab to share. `view` and
`onViewChange` stay on the panel and on the expanded dialog's initial scroll target only: expanding on the
"Tệp gốc" tab opens the dialog at the original, expanding on "Đoạn trích" opens it with the citation rail
focused. The panel's existing "one reader at a time" rule is unchanged.

### Frontend: the reading layout

In the dialog, the original and its citations show at once, in the shape Dovetail and Zillow use for the
same job:

```
┌─ Báo cáo tài chính Q3.pdf ─────────────────────── ⤓ ⛶ ✕ ┐
│ thumbnails │        original document        │ citations │
│ (PDF only) │     (white sheet, elevated)     │  rail     │
│            │ ── floating toolbar ──          │           │
└────────────┴─────────────────────────────────┴───────────┘
```

- **Citation rail** replaces the footer's segmented match switcher. One card per entry in
  `selection.matches`, carrying the passage text, its page when known, and its location confidence.
  Activating a card scrolls the document to its highlight. Its heading says where the passages came from,
  because the two surfaces mean different things by them: a Chat answer cited exactly the passages listed,
  while Search ranked the document's passages and kept only the strongest three. The search heading
  therefore reads "3 đoạn khớp nhất" and carries no separate count badge — a bare number beside "Các đoạn
  được trích dẫn" reads as every match in the document, which is what sent readers looking for marks that
  were never claimed. The "Toàn bộ đoạn trích" tab sits beside it as the way to see the rest.
- **Thumbnail rail** renders only for paged originals (PDF), lazily, and is absent for every other format
  rather than faked.
- **Floating toolbar**, bottom-centre: zoom out / level / zoom in, a page field with total, and previous /
  next citation. It is the same component for every format; controls a format cannot support are not
  rendered.
- **Header actions**: download the original, open at the provider, full screen, close.
- **Window size** comes from the existing `previewSize` kind map (`full` for PDF, workbooks, images and
  DOCX; `large` for code and text), replacing the fixed 56rem × 48rem.
- **Narrow screens**: the citation rail becomes a bottom sheet and the thumbnail rail is hidden.

Passages remain reachable in full: the rail holds the cited ones, and the passages reader stays available
for paging through the whole extraction, including the `fileId` path that has no Document original.

### Frontend: states and theming

- Loading renders a page-shaped skeleton on the canvas, not a centred line of text.
- Four distinct outcomes replace the single red card: original unavailable or changed, original larger than
  the 64 MiB ceiling, original empty, and format not renderable. Each offers the download.
- The canvas uses `surface-sunken`, which is themed. The document sheet stays `surface-document` (#ffffff in
  both themes) because documents are authored on white and the highlighter multiplies onto it — the rule
  `DocumentPdfView` already follows, now applied to DOCX by rendering `docx-preview` output inside a fixed
  light context instead of inverting its text.

## Excluded

- **PPTX inline rendering.** The existing deck path converts to PDF inside the interpreter sandbox and only
  for generated artifacts; converting ingested decks needs LibreOffice in the Worker, which is its own
  increment. Decks get the download panel.
- **Anchors recorded at ingestion time.** Character offsets or element references stored per block would
  make every citation `exact`, but they require an ingestion schema change and a full re-index, and overlap
  MEM-63. Recorded as the follow-up that upgrades `approximate` to `exact`; the viewer reads them without
  further UI change when they exist.
- **Owner-private chat files (`fileId` path).** They keep the passages view and their existing artifact
  content route; they gain the shared viewers only through `ChatFilePreviewModal`, which already owns them.
- **Google-native files (Docs/Sheets/Slides).** They reach the dialog under their exported media type
  (PDF/XLSX/DOCX) and follow the table above.
- **Editing, annotations, presigned URLs** that take the API out of the byte path.
- **A standalone viewer route.** The viewer stays a dialog over Search and Chat; a shareable document URL is
  a separate navigation decision.

## Boundaries

- Authority is identical to the passage readers and rechecked per request and per range, for the original
  and the spreadsheet routes alike — no new permission surface.
- No new frontend dependencies; `docx-preview`, `pdfjs-dist`, `react-shiki` and `dompurify` are already
  shipped, and the CSS Custom Highlight API is a browser primitive.
- No new backend dependency; POI already parses workbooks. `SpreadsheetPreview` moves capability, it is not
  rewritten.
- No schema or storage change; originals are already stored objects with declared media type.
- Moving the Chat viewers changes no Chat behaviour. Any behavioural change there is a defect of this
  increment, not a decision of it.
