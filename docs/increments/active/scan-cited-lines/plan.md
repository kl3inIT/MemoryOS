# Plan: a scan citation marks the question in its line

[Design](design.md). One pull request, committed by concern.

## Backend

1. `PaddleOcrVlClient.Task` (`LAYOUT`, `LINES`) chooses the request fields and the log events. Done.
2. `PaddleOcrVlDocument.lines` and the shared `Placement` of a page's pixels. Done.
3. `OcrReader.read(..., lines)`: a Source scan asks for lines, a Chat attachment and the probe do not;
   `PaddleOcrVlExtractor` sends the second request and keeps the document when it fails;
   `DocumentAssembly.publish` drops lines that would pass the artifact bound; `parser_configuration` records
   `lines=spotting-v1`. Done.
4. `ExtractedDocument.lines` and `Line`. Done.
5. `DocumentChunk.linesJson`, the chunker writing each passage's lines, V201 `document_chunks.lines_json`, the
   repository writing it and reading one passage's lines as `PassageLine`. Done.
6. `DocumentSearchService.passageLines` under the Search reader's authority, `GET
   /api/search/documents/{documentId}/passages/{ordinal}/lines`, `openapi.yml` and the generated client. Done.

Tests: `PaddleOcrVlClientTest` (the spotting request), `PaddleOcrVlDocumentTest` (placement, ownership, refusals),
`PaddleOcrVlRoutingTest` (two requests for a Source scan, one for a Chat scan, a failed lines answer keeps the
document), `StructuredDocumentChunkerTest`, `SearchIndexWorkIntegrationTest` (stored and read back),
`DocumentSearchServiceTest` (authority), `OpenApiContractTest`.

## Web

7. A query for the active citation's passage lines in the Search reader (`passage-lines.ts`). Done.
8. `scan-line-marks.ts`: where the phrase or a word lies in each line, across two lines; `wordSpans` in
   `ink-outline.ts` reads the line's word spaces from its ink, so the question is marked word for word. Done.
9. `ImageView` and the PDF reader take the lines and the question; `CitationRegion` draws a marked stroke as now and
   the others faint, or the marked lines' quadrilaterals when the pixels cannot be read. Done.

Tests: `scan-line-marks.test.ts`, `ink-outline.test.ts` (`wordSpans`), `citation-region.test.tsx`, `image-view.test.tsx`, cases in
`tests/e2e/search.spec.ts` with the lines endpoint answered by the test.

## Documents

10. `docs/specs/ingestion.md` (the second request), `docs/specs/document.md` (lines in the artifact and passages),
    `docs/specs/search.md` (the endpoint and the viewer), the matching `docs/tests/` matrices, the runbook's reindex
    note, the roadmap's active row. Done.

## Acceptance

- Local: unit and browser tests, screenshots against the mock API for the product owner.
- Staging, after merge: a scan reindexed in a Source shows the keyword's line marked and its neighbours faint;
  `paddleocr_vl.lines.completed` `elapsed_ms` is compared with `paddleocr_vl.request.completed` before production.
