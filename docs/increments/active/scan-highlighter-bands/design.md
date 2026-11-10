# Scan citations drawn as highlighter strokes

## Problem

A citation into a scan or a picture marks each text line with a band around its ink
([citation-line-hugging](../citation-line-hugging/design.md)). On staging (2026-11-10, the "OCR mẫu thử"
scan) the product owner found the bands smudgy on straight, tilted and curved lines alike. The cause is in the
viewer, not the OCR, whose outline only bounds the block:

1. The upper edge is the sliding minimum of each column's highest ink pixel and the lower edge the sliding
   maximum of its lowest. Every Vietnamese accent (ể, ỗ, ẳ) and tall letter lifts the upper edge; every
   descender and dot below (g, p, y, ạ) drops the lower one.
2. The edges are smoothed over 0.8 letter heights, less than a letter, so each bump survives.
3. A 1 px edge in the highlight's darker step traces every bump.

The approved sketch with the prototype run on the repository's scan fixture and a synthetic page is
`C:\Users\Admin\Desktop\plan\scan-highlight-de-xuat.html` (2026-11-10), outside the repository; variant A.

## Decision: one model for every line, a centre and a thickness

`inkLines` keeps its input, its refusals and its output (one polygon per line, in the image's pixels), so the
PDF reader, the image viewer and the e2e checks keep calling it as before. What changes is the polygon:

- **Centre.** For each joined part (a line), the centre of each column is the mean row of its ink, so an
  accent or a descender barely moves it; columns between letters are interpolated. The centre is smoothed by a
  straight line fitted around each column over two letter heights, weighted by ink and by a tricube of the
  distance (local linear regression): it follows any curve and does not sag at the ends, where only one side is
  seen. When the smoothed centre stays within 0.4 of the line's median height of one straight line through the
  whole line (away from the ends), that straight line is the centre. A straight or tilted line is the curve's
  special case, not a second path.
- **Thickness.** Measured across the line at each column (the local slope's cosine), from the 20th percentile
  of the columns' tops to the 80th percentile of their feet, with 0.12 letter heights of room: accents and tall
  letters that most columns reach are covered, a lone accent or descender does not size the stroke. The centre
  is moved along its normal to the middle of that span. Lines of one block within 35 % of their usual thickness
  take it, so a paragraph reads as one hand.
- **Stroke.** The centre is simplified (Douglas–Peucker, 0.5 px) and widened by half the thickness on both
  sides along its normals, with round ends, as one polygon.
- **Drawing.** `CitationRegion` fills the line strokes without an edge and clips them to the OCR's outline,
  which stays the boundary as before; the outline drawn before the pixels are read, and an outline without
  lines, keep their edge. Band polygons carry `data-band`, so a check of what is marked ignores the clip.

Measured on the sketch's pages: straight and tilted lines stay within 1–4 px of one straight line, arched
headings 28–35 px from it.

Rejected:

- *Variant B, an underline along the baseline.* Clearest over the text but hard to see on a whole page.
- *Drawing an SVG stroke along the centre.* The region's SVG is scaled non-uniformly (`preserveAspectRatio="none"`),
  which would distort a stroke's width; a polygon in the region's fractions is drawn exactly.
- *Line boxes from the OCR (PaddleOCR-VL spotting).* Rejected in citation-line-hugging for its cost; unchanged.

## Boundaries

- Web only: `ink-outline.ts` and `citation-region.tsx`. No OCR, backend or API change.
- The safety refusals (too little ink, ink over half the outline, low contrast, more than 60 lines, too many
  pixels, unreadable pixels) are unchanged and still fall back to the outline.
- PDFs with a text layer keep the sentence and phrase marks of
  [search-precise-highlight](../search-precise-highlight/design.md); this changes how a scan's lines are drawn.
