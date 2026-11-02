# Cited lines drawn around their ink

## Problem

[citation-polygons](../citation-polygons/design.md) draws a cited scan block in the outline PaddleOCR-VL's
layout model answers. On staging (2026-11-02, `kiem-thu-vung-trich-dan.pdf` in the "OCR mẫu thử" Source) that
outline follows the block, not its text: PP-DocLayoutV3 segments the block's region with a margin, and PaddleX
simplifies the mask (`approxPolyDP`) to a few points. A tilted line came back as a 10-point band with a notch
and a wide end; an arched heading as a 16-point blob that also covers the hollow under the arch's ends. The
reader asked for the mark to follow the text lines themselves.

What is cited still does not change: a citation marks the blocks of its chunk. This increment only draws each
block's text lines tightly inside the outline the OCR already gave.

## Evidence

**What the services can answer** (PaddleX v3.6.1 source and PaddleOCR documentation, read 2026-11-02):

- PP-DocLayoutV3 has no line granularity: its masks are per layout element.
- PaddleOCR-VL 1.5 and 1.6 have a `spotting` task (`promptLabel: "spotting"`), but PaddleX serves it only with
  layout detection off, for the whole page, as another full VLM generation, and each line is a 4-point
  quadrilateral (PaddleOCR-VL-1.5 paper): it follows a tilted line, not an arc. It is in the code and absent
  from the serving documentation.
- A DB text detector with `box_type: poly` follows curved lines, but it is a second PaddleX process or
  container, its box type is a model configuration rather than a request field, PP-OCRv6 needs PaddleX 3.7,
  and nothing measures it on curved document lines or Vietnamese.

**What the page's own pixels give.** A prototype on the staging page's real outlines (NumPy, the algorithm
below) drew the arched heading as one 46-point band along the arc with the hollow left unmarked, the tilted
line as one 69-point band along the line, and the upright paragraph as three bands, one per line. The page,
outlines and renderings are in this session's scratchpad (`staging-ocr/hug-compare.png`).

## Decision

### The viewer tightens an outline to the ink inside it

For a box with an outline, once the page or picture is drawn, the viewer reads the pixels under the outline's
bounds and replaces the outline by one band per text line:

1. Only pixels inside the outline count. Grey levels are split into ink and paper by Otsu's threshold over
   those pixels.
2. The letters of a line are joined, not the lines: the ink is dilated horizontally by 1.2 letter heights and
   vertically by a sixth of one, then held to the outline. The letter height is the 75th percentile of the
   ink's run in each column.
3. Each joined part is one line. Along it, each column's highest and lowest ink pixel give the band's upper
   and lower edge; gaps between letters are bridged, each edge is widened and smoothed over 0.8 letter
   heights, and padded by an eighth of a letter height (at least 3 px).
4. Each edge is simplified (Douglas–Peucker, 2 px) and the band is the upper edge then the lower edge.

The outline stays the boundary: the bands are built from ink inside it, so the mark never reaches another
passage. The outline is drawn as before when the result cannot be trusted: fewer than 30 ink pixels, ink over
half the outline (a picture, a seal, a dark background), less than 40 grey levels between ink and paper, more
than 60 lines, a region over 4 million pixels, or pixels that cannot be read.

Columns are the band's axis, so text written vertically is not tightened well; such a block keeps a band
around its ink, still inside the outline.

Rejected:

- *PaddleOCR-VL spotting.* A second full VLM pass per page for quadrilaterals that do not follow an arc.
- *A DB detector in poly mode.* A new serving container and a PaddleX upgrade for curved lines nobody has
  measured on documents.
- *Tightening every box, outline or not.* An upright box's rectangle is already close, and without an outline
  a table's rules or a figure inside the box would be read as text; the outline is what bounds the read.

### Where it runs

`ink-outline.ts` holds the algorithm on an RGBA array, free of the DOM, and one function that copies the
outline's bounds from a drawn source (the PDF page's canvas, or the decoded picture) into a canvas of that size.
The PDF reader runs it for a page's boxes when the page reports it was drawn; the image viewer when the picture
has loaded. Both run it after the paint, keep the result by box, and draw the bands with the same region
element, slot and label. Where the pixels cannot be read (jsdom, a tainted canvas), the outline is drawn.

### Contract

No backend, storage or HTTP change. The location's `polygon` keeps meaning the OCR's outline; the bands are
the viewer's drawing of it (`docs/specs/document.md`).

## Boundaries

- The backend, `openapi.yml` and the generated client are not edited.
- Boxes without an outline, the rail's located/not-found report and the text viewers are unchanged.
