# Plan

## Steps

0. **Probe the service** — not done before building: the pinned PaddleX source shows PP-DocLayoutV3 answers a
   mask per block and `auto` turns it into an outline (design, Evidence). Still to do on staging: send the serving node's `paddleocr-vl-api` three pictures with the adapter's own
   flags plus `"layoutShapeMode":"auto"`: a scan placed crooked, a photograph of a curved page, an upright
   page. Record for each block whether `block_polygon_points` is present, its point count, its JSON form and
   whether any point leaves `block_bbox` or the picture. Keep one answer with outlines as a fixture beside the
   existing ones (`PaddleOcrVlDocumentTest` resources).
1. **A box may carry its outline** — `ExtractedDocument.BoundingBox` gains optional `polygon`, omitted when
   null; `ExtractedDocument` validation of at least three finite points. Artifacts and provenance written
   before read and write back unchanged. Done.
2. **The adapter keeps outlines** — `PaddleOcrVlClient` sends `"layoutShapeMode":"auto"`.
   `PaddleOcrVlDocument` reads `block_polygon_points`, scales and moves each point exactly as its box (user
   space for an upright PDF page, the frame for a picture or a turned page), and keeps it under the rules in
   the design (inside the frame or CropBox, not the rectangle itself, at most 64 points).
   `parser_configuration` records `outlines=block-polygon-v1`. Done; the rule is "within a pixel of the box",
   stricter than the frame, because the viewers draw the outline inside the box's place.
3. **The viewers draw outlines** — `readBox` reads `polygon`; `frameBoxRect` and `pdfBoxRect` return the
   outline relative to the placed rectangle; `ImageView` and the PDF reader draw an SVG polygon for a box
   with one and the rounded `span` otherwise, same tokens, `data-slot`, label and scroll target
   (`citation-region.tsx`). Done.
4. **Documentation** — `docs/specs/document.md` (the `polygon` member of a location),
   `docs/tests/` matrices for document and preview, the roadmap row, and the runbook note that a reindex
   brings outlines to a document read before. Done.

## Verification

| What | Where |
| --- | --- |
| A box with an outline survives the artifact and chunk provenance; one without is written as before | `StructuredDocumentChunkerTest`, `ExtractionArtifactReaderTest` |
| The request names `layoutShapeMode` | `PaddleOcrVlClientTest` |
| An outline is kept in the box's space for an upright page, a turned page and a picture; dropped when outside, degenerate, too long or the rectangle itself; a block without one keeps its rectangle | `PaddleOcrVlDocumentTest`, with the fixture from step 0 |
| Provenance with an outline reads it; a malformed one reads as the box alone | `source-provenance.test.ts` |
| An outline is placed relative to its rectangle on an upright page, a turned page and a picture, and refused with its box | `pdf-pages.test.ts` |
| A box with an outline is drawn as a polygon, one without as today, in both viewers | `image-view.test.tsx`, a PDF reader test beside it |
| A crooked picture's citation is drawn as its outline in a browser | `search.spec.ts`, a case beside the existing image case |
| The gates | `./gradlew clean check`, `pnpm --dir web check` |

## Open

- Step 0 on staging, then real samples as below.
- Real samples through the staging OCR after the change, as citation-boxes lists them (crooked scan, curved
  page, sideways and upside-down pictures, `/Rotate` pages): each outline on its cited block, or the
  rectangle, never a shape on another passage.
- Lines within a block are out of scope; see the design.
