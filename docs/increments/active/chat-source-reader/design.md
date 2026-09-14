# Chat source reader — navigation, table pages and PDF resilience

Follow-up to [MEM-87](../../completed/mem-87-citation-evidence-ux/design.md) and its whole-document PDF view by HTTP range (PR #155). Owner decisions: 2026-09-15 — step between sources in place, open table rows on their PDF page, expand a document citation into the large preview dialog, and ship these together with the PDF load fallback in one pull request.

## Baseline (origin/main 7860890)

- The Chat source panel opens a citation chip straight on that source and the "Sources" button on the list; reaching another source needs Back and a second choice.
- Evidence tabs always open on "Passages". Docling table rows are chunked as `Row: …` / `<column>: <value>` lines (`StructuredDocumentChunker`), so a financial statement row reads as flattened text with OCR errors, while its page keeps the columns.
- The panel is 400 px wide; the Search preview dialog is up to 56 rem.
- On staging a 9 MB original ("HUT_Baocaotaichinh_Q1_2026_Hopnhat.pdf") showed "The original PDF could not be opened" although every `206` range was correct (`bytes 8388608-9193539/9193540`) and the API logged nothing. Five local PDFs (4–22 MB) opened by range under the production CSP with a fast cancelled first response, so the pdf.js error for that file is unknown.
- Scanned PDFs lost their JBIG2/JPEG 2000 images: pdf.js had no `wasmUrl`.

## References

ChatGPT, Perplexity, Mistral Le Chat, Microsoft Copilot and Gemini Notebook open citations in a side panel next to the answer (Mobbin, 2026-09-15); Manus and Fabric open a document from chat in a large dialog with an expand control; Docusign, Trello and WRITER use centered dialogs for file previews outside a conversation.

## Design

### Stepping between sources

The panel header shows `Source n / total` with previous/next buttons when a source is open and the answer has more than one; ends are disabled, no wrap-around. Stepping keeps focus on the pressed button (the title receives focus only when that button becomes disabled). The citation chip's `aria-current` follows the selection. Back still returns to the list.

### Table rows open on their page

`readSourceLocation` reports `table` when any provenance record carries `tableRow`, which `StructuredDocumentChunker.tableLocation` writes for every table-cell chunk. `EvidenceViewSwitch` opens on "PDF pages" for such PDF evidence and on "Passages" otherwise, in both Chat and the Search preview. Rows emitted without `tableLocation` (a table block without cell data) keep the passages default. The panel remembers the chosen tab per source and passes it to the expanded dialog.

### Expanded view

A wide panel shows an expand button for indexed document citations (not Web pages or whole-file attachments). It opens `DocumentPreviewDialog` with `variant="chat"`: passages through `readChatDocumentPassages` (or the owner-private file passages) and the original through the Chat endpoint, so Chat members without `SEARCH_READ` can use it. While the dialog is open the panel body is a short note, so only one reader and one pdf.js document exist; the panel's Escape handler yields to the dialog, and closing returns focus to the expand button. Narrow screens already show the panel as a full-height sheet and get no expand button.

### PDF load resilience

- If range loading fails, the view reloads the original once with `disableRange` (the former whole read) and reports the failure to Sentry as workflow `pdf-view`, stage `range-load` or `whole-load`; the report carries the pdf.js error for the staging file above.
- pdf.js image decoders (JBIG2, JPEG 2000) are emitted from `pdfjs-dist/wasm` to `assets/pdfjs-<version>/` and passed as `wasmUrl`. The CSP forbids WebAssembly, so pdf.js uses their JavaScript fallbacks.

## Out of scope

Opening a list with a single source directly on it; scrolling the answer to the active chip; ICC colour profiles (`qcms`, WebAssembly only); changing the CSP.
