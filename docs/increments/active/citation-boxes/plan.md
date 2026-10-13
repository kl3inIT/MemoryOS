# Plan

Design checkpoint: the steps below start after the [design](design.md) is accepted.

## Steps

1. **A box may carry its frame** — `ExtractedDocument.BoundingBox` gains optional `frame_width` and
   `frame_height`, omitted when null. `ExtractionArtifactReaderTest`, `StructuredDocumentChunkerTest`: a framed
   box survives the artifact and chunk provenance; an unframed one is written as before.
2. **C1, image boxes** — `PaddleOcrVlDocument` keeps an image's boxes in the picture's own pixels;
   `PaddleOcrVlProperties.parserConfiguration` records `boxes=displayed-frame-v1`. `PaddleOcrVlDocumentTest`
   (the two image cases, a missing size, a box outside the picture), `PaddleOcrVlRoutingTest` where it asserts
   the configuration.
3. **C3, rotated pages in the adapter** — boxes of `/Rotate` 90, 180 and 270 pages in points of the displayed
   page; an answer with the stored page's proportions keeps the page alone. `PaddleOcrVlDocumentTest`.
4. **C3, rotated pages in the viewer** — `readSourceLocation` reads the frame; `pdfBoxRect` places a framed box
   on a page of any rotation and still refuses an unframed one on a rotated page. `source-provenance.test.ts`,
   `pdf-pages.test.ts`.
5. **C2, the image viewer** — `frameBoxRect`, the highlight layer in `ImageView`, the image case of
   `FilePreview`, the rail's report in `DocumentPreviewDialog`. `pdf-pages.test.ts`, a new
   `image-view.test.tsx`, `file-preview.test.tsx`; one browser case in `search.spec.ts` (an image original
   with a cited region, zoomed and turned) on the private harness.
6. **C4 and C5, documentation** — `docs/specs/ingestion.md`, `docs/specs/document.md`,
   `docs/tests/ingestion.md`, `docs/tests/document.md`, the reindex procedure in
   `docs/runbooks/docling-extraction.md`, the roadmap row, this plan and `verification.md`.

## Verification

| What | Where |
| --- | --- |
| Backend unit tests of the adapter, the artifact reader and the chunker | `./gradlew :memoryos-extraction:test :memoryos-core:test --tests …` (no container) |
| Module rules still hold | `ExtractionDependencyRulesTest`, `ModulithArchitectureTest`, `CoreDependencyRulesTest` |
| The HTTP contract did not move | `OpenApiContractTest` without the write flag |
| Web types, units, lint, format, catalog, dead code, build and bundle budget | `tsc -b`, Vitest on the touched files, oxlint, oxfmt, `i18n-audit`, knip, `vite build`, the two assert scripts |
| The image original marks its citation in a browser | the private harness, `search.spec.ts` case |

Left to CI: every Testcontainers suite (`clean check`), the full Playwright suite. Not verifiable anywhere in
this increment: a real PaddleOCR-VL answer for an image and for a `/Rotate` page, and the reindex of documents
indexed before; the design says what each rests on.
