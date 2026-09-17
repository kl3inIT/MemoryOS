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

1. **Where.** The file name on each generated-file card opens the preview in the existing Chat side panel that already reads attachments and `render_gui` artifacts (a right drawer on narrow screens); download stays on the card and in the panel. Onyx uses a modal; MemoryOS reuses its panel so one file surface keeps focus return and Escape handling.
2. **Variants, in the Onyx order.**
   - image (PNG/JPEG/WebP): the served content URL.
   - pdf: the existing pdf.js `DocumentPdfView` from Search instead of an iframe, because the content route serves PDFs as attachments under `nosniff` and MemoryOS already ships the reader.
   - csv: parsed in the browser with a quote-aware parser (Onyx splits on commas, which breaks quoted cells; departure) into the shadcn `Table`, with row and column counts.
   - xlsx: the new preview route below, one tab per sheet, each rendered as the csv variant.
   - markdown: highlighted source. The answer `MarkdownText` renderer needs a message part, and rendering model-written markdown outside an answer would need its own link policy; departure from Onyx's rendered markdown.
   - docx: `docx-preview` plus DOMPurify with the Onyx allowlist; `.doc` shows the Onyx legacy message.
   - text, json and code: the existing Shiki `HighlightedCode`, JSON pretty-printed.
   - anything else: the unsupported state with the download action (pptx too until step 5 lands).
3. **Spreadsheet preview route.** `GET /api/chat/file-artifacts/{id}/preview` with the content route's owner authorization returns `{sheets: [{name, csv, truncated}]}` for xlsx only (other types 400). Parsing uses Apache POI's streaming `XSSFReader` (already used by the connector), writes CSV with quoting, stops a sheet at 500 000 characters at a row boundary, and bounds the workbook by the 25 MiB artifact size and POI's zip-bomb ratio. Formula cells show their cached values, which `recalc-xlsx` fills.
4. **Text size.** Text-like variants read at most 1 MiB and say the preview is truncated; Onyx reads the whole file.
5. **pptx (second step, owner-requested).** Onyx Chat does not preview pptx; Onyx Craft converts it with LibreOffice inside its sandbox. MemoryOS converts it to PDF inside the interpreter executor (network-less, disposable), never in the API process, then shows it with the pdf variant. The executor has only LibreOffice Calc, so this step first measures the Impress image cost and conversion time and caches one PDF per artifact.

## Security

- All bytes come from the owner-authorized artifact routes; no new public URL.
- docx is rendered into detached elements; only its DOMPurify-sanitized HTML is attached, and only library `<style>` elements are moved into the page; no HTML or SVG from a generated file is inserted unsanitized. SVG is not previewed inline.
- The spreadsheet parser never evaluates formulas and runs with bounded input.

## Out of scope

Artifact listing per session, versions, editing an existing artifact, previews of user attachments or search sources.
