# Cited regions drawn in the block's own shape

## Problem

A citation into a scan or a picture is marked with one upright rectangle per block
([citation-boxes](../citation-boxes/design.md)). A block whose lines run at an angle, or along a curve,
gets the upright rectangle that encloses it: for a line tilted a few tens of degrees that rectangle covers
a band of the page far wider than the line, other passages included, and for a curved heading it covers the
hollow of the arc as well. The citation still marks the right block; it marks a great deal more around it.

What is cited does not change here. A citation marks the blocks of its chunk, as today; this increment only
draws each block closer to where its text actually is.

Where the shape is lost today:

1. PaddleOCR-VL answers each block of `prunedResult.parsing_res_list` with `block_bbox`
   (`[x1, y1, x2, y2]`, pixels) and, when its layout model found one, `block_polygon_points`.
2. `PaddleOcrVlDocument.blocks` reads `block_bbox` only (`PaddleOcrVlDocument.java:144`) and writes
   `ExtractedDocument.BoundingBox(l, t, r, b, …)`, which has no place for a shape.
3. The box travels unread as JSON in the artifact, `document_chunks.provenance_json`, the OpenSearch
   `provenance` field and the `provenanceJson` string of the Search and Chat responses.
4. `readBox` (`source-provenance.ts:84`) reads `l`, `t`, `r`, `b` and the frame; `ImageView`
   (`image-view.tsx:157`) and the PDF reader (`pdf-view.tsx:324`) draw one positioned rectangle per box.

## Evidence on the service

The deployment pins PaddleX 3.6.1 (`PaddleOcrVlProperties.DEFAULT_REVISION`, the image digest in
`compose.serving.yaml`). Its source at tag `v3.6.1` was read for this increment:

- **Request.** `paddlex/inference/serving/schemas/paddleocr_vl.py`: `InferRequest.layoutShapeMode:
  Literal["rect", "quad", "poly", "auto"] = "auto"`, passed to the pipeline as `layout_shape_mode`
  (`basic_serving/_pipeline_apps/paddleocr_vl.py`). `PaddleOcrVlClient` does not send it, so every request
  today runs with `auto`.
- **Answer.** `pipelines/paddleocr_vl/result.py`, `_to_json`: a block whose `polygon_points` is not `None`
  gains `block_polygon_points`; the serving layer's `prune_result` removes only `input_path` and
  `page_index`.
- **Shape per mode** (`models/layout_analysis/processors.py`, `_normalize_layout_polygon`): `rect` returns the
  four corners of the upright box; `quad` the polygon reduced to four points, clockwise from the top left;
  `poly` the polygon as found (any number of points, a rectangle below four); `auto` chooses per block: the
  rectangle when it overlaps the four-point shape by 95% or more, the four-point shape when that overlaps the
  polygon by 80% or more, otherwise the polygon.
- **Space.** The points are pixels of the picture the layout model read, float32 pairs `[x, y]`, the space
  `block_bbox` is in. With the document preprocessor off, which the adapter requests and checks
  (`model_settings.use_doc_preprocessor`), that is the page or picture as sent, as for the box.
- **Where they come from.** The layout processor takes the model's `masks` or `polygon_points`; a model that
  emits neither leaves the mode at `rect` with a warning, and no block carries a polygon. PP-DocLayoutV3 is
  one of the two layout models the predictor runs with an image size (`models/layout_analysis/predictor.py`),
  and the Paddle runner's output for it is `[boxes, box_nums, masks]` (`object_detection/predictor.py`,
  `_format_output`): a mask per block, turned into an outline by `extract_polygon_points_by_masks` →
  `mask2polygon` (`cv2.findContours`, `approxPolyDP`). PaddleX describes the model as predicting multi-point
  boxes for skewed and warped pages, which is what it adds over PP-DocLayoutV2.
- **Newer releases.** PaddleX 3.7.0 to 3.7.2 (June 2026) add PP-OCRv6, multi-page TIFF serving and
  `returnMarkdownImages`; nothing in layout analysis or `layoutShapeMode` changed, and PaddleOCR-VL-1.6 at the
  pinned commit is the model's latest. No upgrade is needed for this increment.

What this leaves open, settled by a real answer through the staging OCR (plan, Open):

- How a NumPy array of points serializes in the answer. The adapter reads nested JSON arrays of numbers and
  ignores any other form, so a different form leaves the box as it is rather than misplacing it.
- How often `auto` answers an outline for the crooked scans and photographs MemoryOS receives.

No answer with `block_polygon_points` is recorded in the repository: both fixtures were made before this
question was asked.

## Decision

### A box may carry its outline

`ExtractedDocument.BoundingBox` gains an optional `polygon`: the block's outline as a list of `[x, y]`
points, in **the same space and origin as the box's own `l`, `t`, `r`, `b`**. A user-space box
(`BOTTOMLEFT`, no frame) has its outline in user-space points, y up; a framed box (`TOPLEFT` with
`frame_width`, `frame_height`) has it in the frame's unit, y down. The rectangle stays and stays required: it
is the outline's bounds, what every reader that ignores `polygon` keeps drawing, and what scrolls the cited
block into view.

```json
{"page_no": 1, "bbox": {"l": 158, "t": 286, "r": 1520, "b": 876, "coord_origin": "TOPLEFT",
                         "frame_width": 1685, "frame_height": 1191,
                         "polygon": [[170, 286], [1520, 512], [1508, 876], [158, 650]]}}
```

The component is omitted when null, so every artifact and provenance string written before reads and writes
back byte for byte, and a document read before this keeps its rectangles.

Rules the adapter applies before it keeps an outline:

- At least three points, every coordinate finite, the area not zero.
- Every point within one pixel of the box, after the same scaling the box gets, then held to it; an outline
  that leaves the box is dropped and the block keeps its rectangle. The viewers draw the outline inside the
  place the box takes, so an outline past the box would be drawn where nothing was measured.
- An outline that is the rectangle itself (four points on the box's corners) is not written: it adds bytes
  and draws the same thing.
- At most 64 points; a longer outline is dropped rather than simplified, since a simplification would be this
  adapter's invention.

Rejected:

- *Replacing the rectangle by the outline.* Readers written before, the scroll target and the citation-boxes
  checks all work from the rectangle; a shape that only adds what it knows breaks none of them.
- *A rotated rectangle (centre, size, angle).* It fits a straight tilted line but not a curved one, and the
  service already answers with the outline; deriving an angle from it would discard information to add a
  second format.
- *Points as fractions of the frame.* For the same reason as citation-boxes: the viewer compares the recorded
  frame with the picture it shows, and a box and its outline in two different units would invite drawing one
  without the other.

### The request names its shape mode

`PaddleOcrVlClient` sends `"layoutShapeMode":"auto"` with the flags it already sends. `auto` is the service's
default today; naming it keeps a change of default from silently removing outlines, as the adapter already
names the preprocessor and chart flags. `poly` was considered: it returns the raw polygon even for an upright
block, where `auto` returns the rectangle the adapter then does not write.

### Storage and API

No schema change, no migration, no HTTP contract change: the outline is one more member of the location JSON
that `provenanceJson` already carries as an opaque string, so `openapi.yml` and the generated client stay as
they are. The documented content of that string changes (`docs/specs/document.md`). `parser_configuration`
gains `outlines=block-polygon-v1`, so a document read with outlines is told from one read before.

### The viewers draw the outline

- `readBox` reads `polygon` onto `ProvenanceBox` when it is an array of at least three finite pairs; anything
  else is ignored and the box is read as today.
- `frameBoxRect` and `pdfBoxRect` place the box exactly as today and, when it has an outline, also return the
  outline's points relative to the placed rectangle (fractions of its width and height), using the same
  scaling and the same origin rule. A box they refuse is refused with its outline.
- `ImageView` and the PDF reader keep their one multiplied highlight layer. A box with an outline is drawn as
  an inline SVG `polygon` filling the placed rectangle (`viewBox="0 0 1 1"`,
  `preserveAspectRatio="none"`), with the PDF highlight's fill and border tokens; one without stays the
  rounded `span`. Both keep `data-slot` (`image-citation-box`, `pdf-citation-box`), the accessible label and
  the scroll target, so tests and the rail see the same elements.
- The rail does not change: a citation is placed or not as today, whatever shape it is drawn in.

Rejected: CSS `clip-path: polygon()` on the existing `span`. It cuts the fill but not the ring, so the border
would still draw the rectangle.

Not in this increment: lines within a block (marking only the lines that hold the cited passage needs each
line's text, which this service does not answer); Docling's boxes, which have no outline; the catalog-model
and AI Hay readers, which answer no position; documents indexed before, which keep their rectangles until an
operator reindexes them by the procedure citation-boxes documented.

## Boundaries

- `openapi.yml` and `web/src/lib/hey-api` are not edited; no endpoint, flag, mode or migration.
- `pdf-view.tsx` changes only where a box is drawn. The constraint citation-boxes recorded against editing it
  was another increment's work in flight; check that none is open on the file before starting.
- Docling's adapter and the text viewers are not touched.
