# MEM-111 — Generated file preview

[MEM-111](https://linear.app/memory-os/issue/MEM-111) · related [MEM-110](../mem-110-memoryos-interpreter/design.md)

## Problem

`run_python` stores what it generates as `chat_file_artifact` rows and the answer shows download cards. A user cannot look at a generated workbook, report or chart without downloading it. MEM-111 asks for preview, listing and versioning; this increment delivers **preview** only, following the Onyx Chat preview modal. Listing, versioning and editing an earlier artifact stay in MEM-111's backlog.

## Reference

Onyx `40eb240df`:

- `web/src/sections/modals/PreviewModal/variants/index.ts` picks the first matching variant in the order code, image, pdf, csv, xlsx, markdown, docx, text, data, and falls back to `unsupported` (a download).
- `backend/onyx/server/query_and_chat/chat_backend.py` `fetch_chat_file(parsed=true)` returns a spreadsheet as `{sheets: [{name, csv, truncated}]}` through `parse_spreadsheet_for_preview` (`chat_utils.py`): each sheet as CSV, cut at a row boundary outside quotes past `MAX_PREVIEW_CHARS_PER_SHEET` (500 000). Every other type is served raw.
- `docxVariant.tsx` fetches the bytes, renders them with `docx-preview` `renderAsync` and re-sanitizes the output with DOMPurify, allowing only `http(s)`/`mailto` URIs (`sanitizeDocxHtml.ts`); styles keep only `<style>` children. A legacy `.doc` shows a message.
- `pdfVariant.tsx` embeds the file in an iframe.
- `csvVariant.tsx` renders a table with a row count; `dataVariant`/`codeVariant` render highlighted text with a copy button; `imageVariant` shows the image.
- Onyx Chat has no pptx preview. Onyx Craft renders pptx through `soffice` → `pdftoppm` (`generate_pptx_preview`), inside its sandbox.

## Decisions

1. **Where (owner decision 2026-09-17: as Onyx).** A centered modal like Onyx `PreviewModal`, sized by variant (screen-sized for images, PDF, CSV, xlsx, docx and markdown; a large window for code and text; a tall window for downloads), full screen on phones. The header shows the name and the Onyx description (size · language · lines for code, size · lines for text, size · rows for CSV, sheet count, word count for docx); a floating footer shows line, sheet or column counts, image zoom (25–200%), copy and download. Loading shows a spinner; a failure shows a message with a download button. It opens from a generated-file card, from a `[file](file_link)` link in the answer (Onyx `MemoizedTextComponents` opens the preview instead of downloading) and from a message attachment chip, which previously used the side-panel reader. Citations of attachments keep the side-panel passage reader.
2. **Variants, in the Onyx order.**
   - image (PNG/JPEG/WebP): the served content URL.
   - pdf: the existing pdf.js `DocumentPdfView` from Search instead of an iframe, because the content routes serve PDFs as attachments under `nosniff` and MemoryOS already ships the reader.
   - csv: parsed in the browser with a quote-aware parser (Onyx splits on commas, which breaks quoted cells; departure) into the shadcn `Table`, with row and column counts.
   - xlsx: the new preview route below, one tab per sheet, each rendered as the csv variant.
   - markdown: highlighted source. The answer `MarkdownText` renderer needs a message part, and rendering model-written markdown outside an answer would need its own link policy; departure from Onyx's rendered markdown.
   - docx: `docx-preview` plus DOMPurify with the Onyx allowlist and page layout, as Onyx; `.doc` shows the Onyx legacy message.
   - text, json and code: the existing Shiki `HighlightedCode`, JSON pretty-printed.
   - anything else: the unsupported state with the download action (pptx too until step 5 lands).
3. **Spreadsheet preview routes.** `GET /api/chat/file-artifacts/{id}/preview` and, for attachments, `GET /api/chat/files/{id}/preview` with the content route's owner authorization returns `{sheets: [{name, csv, truncated}]}` for xlsx only (other types 400). Parsing uses Apache POI's streaming `XSSFReader` (already used by the connector), writes CSV with quoting, stops a sheet at 500 000 characters at a row boundary, and reads the workbook (at most the 25 MiB artifact size) from a temporary file, as Onyx, so parts inflate on demand and sheet XML streams; POI's default zip-bomb ratio (0.01) still applies to each part, and the shared-strings part is held in memory. Formula cells show their cached values, which `recalc-xlsx` fills.
4. **Bounds (departure).** Text-like variants read at most 1 MiB and tables render 1 000 rows, each saying the preview is truncated; Onyx reads and renders everything. The attachment route serves `application/octet-stream`, so the modal previews an attachment by its stored media type, as Onyx re-resolves by the stored MIME.
5. **pptx (second step, owner-requested).** Onyx Chat does not preview pptx; Onyx Craft converts it with LibreOffice inside its sandbox. MemoryOS converts it to PDF inside the interpreter executor (network-less, disposable), never in the API process, then shows it with the pdf variant. The executor has only LibreOffice Calc, so this step first measures the Impress image cost and conversion time and caches one PDF per artifact.

## Security

- All bytes come from the owner-authorized artifact routes; no new public URL.
- docx is rendered into detached elements; only its DOMPurify-sanitized HTML is attached, and only library `<style>` elements are moved into the page; no HTML or SVG from a generated file is inserted unsanitized. SVG is not previewed inline.
- The spreadsheet parser never evaluates formulas and runs with bounded input.

## Out of scope

Artifact listing per session, versions, editing an existing artifact, previews of user attachments or search sources.
