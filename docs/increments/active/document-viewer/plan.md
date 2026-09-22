# Plan

Steps 1–2 are a behaviour-preserving move; step 3 is the risky pure logic and is written test-first; steps
4–9 build on both. Each step is one commit, with `clean check` green before the next.

All nine steps are implemented and committed. The open work is live acceptance, recorded under
Verification below.

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
| Citation locator: exact match, whitespace and soft-hyphen differences, capitals, Vietnamese combining marks, flattened table row, anchored match | `preview-highlight.test.ts` |
| Locator returns `none` rather than a wrong range when the passage is absent, repeated, too short to anchor, or its anchors sit too far apart | `preview-highlight.test.ts` — the assertions the step exists for |
| A repeated passage is placed under the most specific heading that occurs once, and stays `none` when no heading singles one out | `preview-highlight.test.ts` |
| A section hands the reader the provenance of the chunk that matched, not of the section it starts in | `source-provenance.test.ts` |
| Chat file preview unchanged by the move | existing `chat-file-preview.test.ts`, `chat-file-preview-gallery.test.tsx` |
| PDF page windowing unchanged by the move | existing `pdf-page-window.test.ts` |
| Any-type original served with declared Content-Type, inline disposition, nosniff | `DocumentOriginalResponsesTest` |
| HTML/SVG served as attachment, not inline | same |
| PDF path keeps magic-byte check and range behaviour | `DocumentOriginalServiceTest`, `SourceOriginalQueryTest` |
| Spreadsheet route returns sheets under the same authority; a Document of another type is not readable as one | `DocumentOriginalServiceTest.aWorkbookOriginalIsReadAsSheetsAndAnythingElseIsNotReadableAsOne`. The controllers delegate without logic of their own, so the authority is tested at the service, which is the narrowest useful boundary; no new Spring integration test was added |
| Inline source panel opens the original first for PDF/DOCX/XLSX/images and the passages first for Markdown and plain text | `evidence-order.test.ts` |
| Citation rail entry hands the citation to the reader; an unlocated entry states so and draws nothing, and an original with no text layer says that instead of blaming the passage | `citation-rail.test.tsx` |
| The chunk header never reaches the locator or the reader | `search-presentation.test.ts` |
| A workbook citation is placed on its recorded sheet row, and a row the preview did not render is left unplaced | `sheet-citations.test.ts` |
| An absent sheet row keeps its line, so a recorded row number addresses the same line of the preview | `SpreadsheetPreviewTest` |
| The rendered sheet marks the cited rows and states which one is being read | `csv-view.test.tsx` |
| A marked row keeps its colour under the pointer, and the expand control opens a true full screen | browser check in Orca on 2026-09-22 |
| A sheet renders only the rows in view, so a wide workbook does not build thousands of cells | `csv-view.test.tsx` plus a browser measurement in Orca on 2026-09-22: the same workbook fell from 11,200 rendered cells to 577 |
| PDF, DOCX and XLSX read correctly in light and dark | browser check in Orca — DOCX confirmed on the Tasco report on 2026-09-22 in dark and light; XLSX confirmed on an uploaded workbook; PDF is unchecked here because this machine has no Docling endpoint, so no PDF can be extracted or indexed |

Not verified here: rendering fidelity of a real Tasco DOCX/XLSX corpus beyond the fixture files, and the
`approximate` match rate against production documents — measured after the first live read, not asserted.

Measured and left alone: a workbook still takes about 2.2 s to open. Virtualizing the table did not move
that number, because the wait is reading and converting the workbook in the API, not building the rows.
Shortening it belongs to the spreadsheet route, not to the reader.

Not delivered: the four distinct unavailable-original panels the design asks for. The reader shows two —
the original could not be opened, and this type cannot be shown here — because an oversized or missing
original both reach the browser as 404 today. Telling them apart needs a specific problem code from the
API, which is separate work.

Found while verifying, and not this increment's to fix: every indexed passage carries the chunker's
`Title:`/`Section:` header in the text Search matches on, so a query hitting a section heading ranks every
cell under that heading. The viewer now shows the passage without the header, which makes those weak
matches visible; the ranking itself belongs to Search.

## Risks

- **The move is the largest diff and carries no new behaviour.** Reviewing it as one commit would hide a
  regression, so each viewer moves separately with the Chat tests green in between.
- **`docx-preview` renders a whole document at once**, so a long DOCX can jank on open. Measured in step 1;
  if it does, the rendered output is sectioned under `content-visibility: auto` before step 7 ships.
- **Anchor matching can still mislocate** within a document that repeats a phrase. The 40% length tolerance
  and the `approximate` label bound the damage; ingestion-recorded anchors remove it, and are the recorded
  follow-up.
