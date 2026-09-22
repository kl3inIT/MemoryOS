# Plan

Steps 1–2 are a behaviour-preserving move; step 3 is the risky pure logic and is written test-first; steps
4–9 build on both. Each step is one commit, with `clean check` green before the next.

## Steps

1. **Extract the shared preview kit** — move `chat/chat-file-preview.ts` to `preview/preview-kind.ts` and the
   per-kind renderers out of `chat/chat-file-preview-modal.tsx` into `preview/{docx,sheet,csv,text,image,
   download}-view.tsx`, one viewer per commit. `ChatFilePreviewModal` keeps its shell and imports them.
   `chat-file-preview.test.ts` and `chat-file-preview-gallery.test.tsx` stay green unchanged — they are the
   proof that nothing moved behaviourally.
2. **Move the PDF viewer** — `search/document-pdf-view.tsx` to `preview/pdf-view.tsx`, unchanged except for
   the toolbar it now delegates to. `pdf-page-window.test.ts` stays green.
3. **Citation locator** — `preview/preview-highlight.ts`, written test-first: flatten and normalise, exact
   match, anchor match with the 40% length tolerance, and `none`. It never returns a range it cannot
   justify; that is the assertion the tests exist for.
4. **Surface and toolbar** — `preview/preview-surface.tsx` (sunken canvas, `surface-document` sheet, page
   skeleton) and `preview/preview-toolbar.tsx` (floating; zoom, page field, previous/next citation, and the
   per-format subset). Highlight painting via `CSS.highlights` with the `::highlight()` rule on
   `--pdf-highlight`.
5. **Service and original routes** — `DocumentOriginalService.searchOriginal(actor, id, generation, range)`
   serving any media type through `sources.originals`; keep `searchPdf`/`citationPdf` for the
   magic-byte-checked PDF path; extend `citationOriginal` with an optional range so the chat route shares one
   shape. `SearchController`/`ChatDocumentController` `/original` pick the path by declared media type and
   set `Content-Type`, `Content-Disposition` (inline; attachment for HTML/SVG/XHTML) and
   `X-Content-Type-Options: nosniff`. Update `openapi.yml`, regenerate the SDK.
6. **Spreadsheet routes** — move `SpreadsheetPreview` from `io.memoryos.chat.interpreter` to
   `io.memoryos.document`, repoint the Chat callers, and add
   `GET /api/{search,chat}/documents/{id}/spreadsheet` resolving through `DocumentOriginalService` under the
   same authority. Update `openapi.yml`, regenerate the SDK.
7. **Inline source panel** — `EvidenceViewSwitch` generalizes from `PdfEvidence` to any media type: tab
   labels "Đoạn trích" and "Tệp gốc", the original first for PDF/DOCX/XLSX/images and the passages first for
   Markdown and plain text. It renders `features/preview` viewers and the citation highlight. `fileId`
   citations keep `ChatFileReader` unchanged. `ChatSourcePanel`'s shared `view` state and its
   "one reader at a time" rule are untouched.
8. **Reading layout** — the dialog drops the tabs for the reading shell: thumbnail rail (paged originals
   only), document canvas, citation rail with per-entry location confidence, header actions (download, open
   at provider, full screen, close), window size from `previewSize`, bottom sheet on narrow screens. The
   incoming `view` selects the dialog's initial focus — original or citation rail — instead of a tab. The
   `fileId` path keeps the passages reader.
9. **Documentation** — `docs/specs/document.md` (original and spreadsheet serving contract, citation
   location contract), `docs/tests/document.md`, `docs/guidelines/design-tokens.md` if the highlight rule
   needs stating there, the roadmap row, and the AGENTS.md active-increment bullet.

## Verification

| What | Where |
| --- | --- |
| Citation locator: exact match, whitespace and soft-hyphen differences, flattened table row | `preview-highlight.test.ts` |
| Locator returns `none` rather than a wrong range when the passage is absent or too short to anchor | `preview-highlight.test.ts` — the assertion the step exists for |
| Chat file preview unchanged by the move | existing `chat-file-preview.test.ts`, `chat-file-preview-gallery.test.tsx` |
| PDF page windowing unchanged by the move | existing `pdf-page-window.test.ts` |
| Any-type original served with declared Content-Type, inline disposition, nosniff | `SearchDocumentApiIntegrationTest` / chat counterpart |
| HTML/SVG served as attachment, not inline | same |
| PDF path keeps magic-byte check and range behaviour | existing original-PDF tests stay green |
| Spreadsheet route returns sheets under the same authority; unauthorized actor still 404/403 | new API integration test |
| Inline source panel opens the original first for PDF/DOCX/XLSX/images and the passages first for Markdown and plain text | component test |
| Citation rail entry scrolls the document to its highlight; unlocated entry states so and draws nothing | component test |
| Unknown type, oversized, empty and unavailable originals each show their own panel with a download | component test |
| PDF, DOCX and XLSX read correctly in light and dark | browser check in Orca |

Not verified here: rendering fidelity of a real Tasco DOCX/XLSX corpus beyond the fixture files, and the
`approximate` match rate against production documents — measured after the first live read, not asserted.

## Risks

- **The move is the largest diff and carries no new behaviour.** Reviewing it as one commit would hide a
  regression, so each viewer moves separately with the Chat tests green in between.
- **`docx-preview` renders a whole document at once**, so a long DOCX can jank on open. Measured in step 1;
  if it does, the rendered output is sectioned under `content-visibility: auto` before step 7 ships.
- **Anchor matching can still mislocate** within a document that repeats a phrase. The 40% length tolerance
  and the `approximate` label bound the damage; ingestion-recorded anchors remove it, and are the recorded
  follow-up.
