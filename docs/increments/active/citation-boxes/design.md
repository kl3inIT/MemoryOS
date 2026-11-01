# Cited regions on images and rotated PDF pages

## Problem

A citation into a scanned picture, or into a PDF page turned by `/Rotate`, opens the original with nothing
marked, and the citation rail says the passage has no text layer or was not found. The OCR service answered
with a box for every such passage; MemoryOS discards it.

Where a box travels today, and where it stops:

1. PaddleOCR-VL answers `result.layoutParsingResults[i].prunedResult` with `width`, `height` and
   `parsing_res_list[].block_bbox` (`[x1, y1, x2, y2]`, pixels from the top left of what it read).
2. `PaddleOcrVlDocument.blocks` turns the box into PDF user space only when the page has a frame and that frame
   has no `/Rotate` (`PaddleOcrVlDocument.java:83-93`, `:103-104`). An image input passes no frames
   (`PaddleOcrVlExtractor.java:45-46`), and a rotated page leaves `frame` null, so both write
   `Location.page(n, null)`.
3. The location is stored inside the extraction artifact (`ExtractedDocument.Location`/`BoundingBox`) and
   copied, as JSON, into each chunk's `provenance_json` by `StructuredDocumentChunker.provenance`
   (`StructuredDocumentChunker.java:101-106`); OpenSearch keeps the same string. There is no column per
   coordinate.
4. The API hands that string through unread: `SearchPageResponse.ChunkProvenance.provenanceJson`,
   `SearchDocumentResponse.Passage.provenanceJson`, `ChatSourceResponse.Provenance.provenanceJson`.
   `DocumentOriginalService` and `DocumentOriginalResponses` serve the original's bytes and carry no location.
5. The browser reads it in `readSourceLocation` (`source-provenance.ts:28`, `readBox` at `:84`), and the
   dialog and the Chat panel pass `boxes` to `OriginalView` → `FilePreview`
   (`document-preview-dialog.tsx:146`, `chat-document-evidence.tsx:33`).
6. `FilePreview` gives `boxes` to the PDF view only (`file-preview.tsx:108`); the image case passes none
   (`:109-120`), and `ImageView` draws none. `pdfBoxRect` returns nothing for a rotated page
   (`pdf-pages.ts:24`).
7. The rail reports a PDF citation from what provenance recorded and treats an image as having no text to
   search (`document-preview-dialog.tsx:101-103`).

A stored box on an ordinary PDF page is in PDF user space: points, origin at the lower left, y up, including
the CropBox's own offset, `coord_origin: BOTTOMLEFT` (PaddleOCR-VL adapter), or whatever origin Docling
reported (Docling adapter). It is the space of the `view` pdf.js reports for the page, which is what
`pdfBoxRect` subtracts.

## Evidence on coordinates

The deployment pins PaddleX 3.6.1 (`PaddleOcrVlProperties.DEFAULT_REVISION`, image digest in
`compose.serving.yaml`). Its serving code at tag `v3.6.1` was read for this increment
(`paddlex/inference/serving/infra/utils.py`, `paddlex/inference/utils/pdf_rendering.py`).

**Image input.** `file_to_images` decodes a single picture with
`cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)` and reports its `shape` as the size; nothing
resizes it (a picture over the pixel limit is refused). PaddleX pins `opencv-contrib-python == 4.10.0.84`,
whose `imdecode_` applies the EXIF orientation unless `IMREAD_IGNORE_ORIENTATION` or `IMREAD_UNCHANGED` is
passed (`modules/imgcodecs/src/loadsave.cpp`, `ApplyExifOrientation`; the same rule is in the `imread`
documentation). So `block_bbox` is in pixels of the picture **after** EXIF orientation, origin top left, y
down, and `prunedResult.width`×`height` is that oriented size. A browser shows a picture the same way
(`image-orientation: from-image` is the CSS default) and reports the oriented size as `naturalWidth`×
`naturalHeight`. A multi-frame TIFF is read frame by frame with Pillow, without EXIF; no browser shows a TIFF,
so the viewer never draws on one.

What this leaves open: no recorded answer for an image input exists in the repository (both fixtures are PDF
answers replayed as if they were images), and the claim about EXIF rests on the pinned OpenCV's source, not
on an observed answer. The two readers can only disagree when one of them ignores an orientation tag. The
viewer therefore draws only when the picture it shows has exactly the recorded size; that catches every
disagreement that turns the picture a quarter (EXIF 5–8) unless it is square, and cannot catch a half turn or
a mirror (EXIF 2–4).

**Rotated PDF page.** `read_pdf` renders each page with pypdfium2:
`page.render(scale=…, rotation=0).to_numpy()`, at `PDF_RENDER_SCALE` 2.0. pypdfium2 sizes the bitmap from
`get_width()`/`get_height()` (`FPDF_GetPageWidthF`/`HeightF`, of which PDFium's header says "Changing the
rotation of |page| affects the return value") and passes `rotation` as an *additional* turn to
`FPDF_RenderPageBitmap`, whose `rotate` 0 is the page's normal orientation. The rendering is therefore the
page as displayed, `/Rotate` applied, and `block_bbox` is in pixels of the displayed page from its top left.
The recorded fixtures agree on the scale and rounding: an 842.04 × 595.44 pt page came back 1685 × 1191
(`ceil(2 × size)`).

What this leaves open: the only rotation recorded (MEM-192, 2026-09-23) is a scan turned *inside* a page
without `/Rotate`. No answer for a page with `/Rotate` is recorded, and none can be made here (no OCR
service). For 90 and 270 the answer verifies itself: a displayed page is landscape where the stored one is
portrait, so the adapter keeps a box only when the answer's `width`×`height` has the displayed page's
proportions. For 180 the two spaces have the same size and the box rests on the PDFium semantics alone.

**What the service does to the picture before it answers.** PaddleX's layout parsing has a document
preprocessor in front of layout detection: an orientation classifier (`useDocOrientationClassify`) that turns
the picture by 0, 90, 180 or 270, and unwarping (`useDocUnwarping`). When it runs, layout is read from its
`output_img`, `prunedResult.width`/`height` are that corrected picture's, and `block_bbox` is in it
(`paddlex/inference/pipelines/paddleocr_vl/pipeline.py` at `v3.6.1`: `doc_preprocessor_images` feed layout and
the result's `"width": doc_preprocessor_image.shape[1]`). A half-turn correction keeps the size, so neither
the proportion check nor the exact-size check sees it; unwarping cannot be mapped back at all.

- *What the adapter requests.* `PaddleOcrVlClient` writes `"useDocOrientationClassify":false,
  "useDocUnwarping":false` into every request, for PDFs and images alike. MEM-192 chose that on 2026-09-23: the
  serving image loads no preprocessing model (`use_doc_preprocessor: False` in its pipeline file), switching
  the classifier on answered HTTP 500, and its test of a scan turned inside an unrotated page showed the
  table read identically and the boxes on the page as displayed with both flags off. Page turning was left
  to nobody: nothing corrects orientation on this route.
- *What the deployment enables.* The pipeline file is inside the pinned image
  (`/home/paddleocr/pipeline_config_vllm.yaml`, named by `compose.serving.yaml`), not in the repository. It
  does not decide the outcome: `get_model_settings` takes the request's flags when either is given, and with
  both `false` sets `use_doc_preprocessor = False` whatever the file says; the pipeline then passes each
  picture through as `{"output_img": arr}`.
- *What the answer reports.* `prunedResult.model_settings.use_doc_preprocessor`, always; and
  `prunedResult.doc_preprocessor_res` (with the angle and the preprocessor's own settings) only when it ran.
  Both recorded fixtures say `"use_doc_preprocessor": false`.

So the case that holds is **both off, requested off by the adapter**, and every framed box is on as designed.
The adapter does not take that on trust per answer: a framed box needs `model_settings.use_doc_preprocessor`
to be `false`. An answer that says `true` keeps no framed box (stricter than "angle 0 only": the request
cannot produce that answer, so nothing reads the angle). An answer without the field, from a service that is
not the pinned one, keeps a quarter-turned PDF page's boxes on the proportion check alone, and none for a
half-turned page or an image.

**`parser_configuration` is diagnostic.** It is written by the four extractors into `DocumentContent.metadata`
and stored as the Document's `metadata_json` (`JdbcDocumentRepository`). No Java, SQL or web code reads the
key or compares the string: nothing decides re-extraction, re-chunking, republication or the served generation
by it. `boxes=displayed-frame-v1` is therefore added.

## Decision

### One more kind of box: measured in the frame as displayed

A box that is not in PDF user space says so by carrying the size of the frame it is measured in:

```json
{"page_no": 1, "bbox": {"l": 158, "t": 286, "r": 1520, "b": 876, "coord_origin": "TOPLEFT",
                         "frame_width": 1685, "frame_height": 1191}}
```

- `frame_width` and `frame_height` present: `l`, `t`, `r`, `b` are measured from the top left of the frame as
  a reader sees it, x right and y down, in the frame's own unit, and the frame is that size. For an image the
  unit is a pixel of the picture after EXIF orientation. For a rotated PDF page the unit is a point and the
  frame is the page as displayed (its `pages[]` entry).
- Absent: the box is in PDF user space, as before. Nothing about an upright PDF page changes, so no stored
  document changes meaning and no chunk convention changes.

`ExtractedDocument.BoundingBox` gains the two optional components, omitted when null, so every artifact and
provenance string written before reads and writes back byte for byte.

Rejected:

- *Fractions of the frame (0–1), normalized at the source.* Self-contained and unit-free, but it throws away
  the one check available against a wrong orientation: the viewer could no longer compare the recorded frame
  with the picture it shows.
- *Rotated-page boxes converted to user space, with `pdfBoxRect` turning them back.* It keeps one coordinate
  space, but the viewer cannot tell such a box from a Docling box on a rotated page, whose frame may be
  orientation-corrected (`docs/specs/document.md`, owned OCR image) and which is stored in documents already
  indexed. Drawing those is the wrong-place risk the present guard exists for. It also adds a rotation in the
  adapter and its inverse in the viewer to arrive where the OCR's own answer already is.
- *The picture's size in `pages[]` instead of on the box.* `pages[]` stays in the artifact; chunk provenance,
  the only thing a reader receives, is the location alone.

### Storage and API

No schema change and no migration: a location is JSON inside `document_chunks.provenance_json`, the artifact
and the OpenSearch `provenance` field. No HTTP contract change: `provenanceJson` is already an opaque string
in the three responses, so `openapi.yml` and the generated client stay as they are. The contract that changes
is the documented content of that string (`docs/specs/document.md`).

Rejected: typed location fields on the search and chat responses. They would duplicate what the string
carries for one reader, in three responses another increment is editing.

### C1 — the adapter keeps an image's boxes

`PaddleOcrVlDocument.blocks` with no PDF frames: with a positive numeric `width` and `height` in the result,
every block's box is kept as it was answered, `TOPLEFT`, with that size as its frame. A box outside the frame
or with no area is dropped and the block keeps its page. A result without a size is still read, its blocks on
their page without a box: the picture's text does not depend on where it is, and no image answer has been
recorded to say the size is always there.

### C3 — the adapter keeps a rotated page's box

For a page with `/Rotate` 90, 180 or 270 the displayed size is the CropBox with width and height swapped for
90 and 270. The box is the answer's pixel box scaled to points of the displayed page
(`displayed width / prunedResult.width`, `displayed height / prunedResult.height`), `TOPLEFT`, with the
displayed size as its frame. It is kept only when the answer's proportions are the displayed page's (the two
scales within 1% of each other); otherwise the page carries `page_no` alone, as today.

`parser_configuration` gains `boxes=displayed-frame-v1`, so a document read before this is told from one read
after it.

### C2, C3 — the viewers

`readSourceLocation` reads the two keys onto the box it already returns. `pdfBoxRect` and a new
`frameBoxRect` in `pdf-pages.ts` place a framed box as fractions of the rendered surface:

- `pdfBoxRect`: a framed box is drawn on a page of any rotation when the frame's proportions are the displayed
  page's (within 1%); a box without a frame keeps today's rule, upright pages only. Its signature does not
  change, so `pdf-view.tsx` is not edited.
- `ImageView` takes the boxes of the active citation and draws the ones on page 1 in a layer that shares the
  picture's transform, so it follows zoom, pan and the viewer's own rotation. The layer is the PDF highlight:
  `bg-pdf-highlight`, `ring-pdf-highlight-border`, one multiplied layer for all regions,
  `data-slot="image-citation-box"`, the same accessible label. It draws only when the loaded picture's
  `naturalWidth`×`naturalHeight` is exactly the recorded frame, and tells its caller which it was.
- The rail: an image citation that recorded a framed box is `exact` once the picture agreed with the frame and
  `none` when it did not; an image with no recorded box keeps "Không có lớp văn bản". The view checks the
  citation being read; every region of one picture is in the same frame, so its answer stands for the other
  citations with a region, and while the citation being read has none they report what was recorded.
- A rotated PDF page: the rail reports a PDF citation from provenance, as before. Whether a framed box was
  refused is known only inside the PDF reader, once the page's `view` has loaded; reporting it needs a
  callback out of `pdf-view.tsx`, which the search-matches increment is editing. It is left as it is and
  listed under Open in the plan. The adapter writes a framed box only with the displayed page's proportions,
  so the reader refuses one only when the page pdf.js shows is not the page PDFBox measured.

Rejected: a canvas overlay (the highlight is a positioned element everywhere else), and scaling a box whose
frame differs from the picture (a resized or re-oriented picture is exactly the case where the box may be
wrong).

Not in this increment: Docling's boxes on an image input (they are in Docling's page frame, with no evidence
here about EXIF) and on rotated pages; boxes for the catalog-model and AI Hay readers, which return text
without positions.

### C4 — documents indexed before

Boxes exist only in a new extraction, so old documents need their original read again, not their chunks
rebuilt. The existing path is Reindex on a Source file: Files tab → the file's menu → Reindex
(`POST /api/sources/{sourceId}/items/{itemId}/index-attempts`, `SourceManagementService.reindex`,
`SOURCES_MANAGE` or the recorded manager). It reads the stored original again, publishes a new generation and
keeps the Document ID; the previous generation stays served until the new one is ready. Nothing is added for
a bulk backfill. The procedure goes in `docs/runbooks/docling-extraction.md`, PaddleOCR-VL section: which
documents are affected (images, and scanned PDFs with turned pages, whose `parser_configuration` lacks
`boxes=displayed-frame-v1`), that the organization's OCR reader must be PaddleOCR-VL, and that each reindex is
one OCR request.

## Boundaries

- `pdf-view.tsx`, `openapi.yml`, `web/src/lib/hey-api` and the fast-open increment's files are not edited.
- `file-preview.tsx` changes in the image case only; the text viewers and the Search surface belong to the
  search-matches increment.
- No endpoint, flag, mode or migration.
