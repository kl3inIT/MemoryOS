# Plan: scan citations drawn as highlighter strokes

Design: [design.md](design.md). Branch `phamnhatanh/scan-highlighter-bands`, web only.

## Steps

1. `ink-outline.ts`: replace the per-column edges with a centre (ink mass, local linear smoothing, a straight
   line when one fits) and one thickness (percentiles across the line, shared within a block); return each line
   as a round-ended stroke polygon. Keep every refusal.
2. `citation-region.tsx`: line strokes filled without an edge, clipped to the outline; `data-band` on them.
3. Unit tests: the existing tilted, arched, paragraph and refusal cases; new cases for straight parallel edges
   on accented letters and one thickness per block; the region's clip and missing edge.
4. Playwright: the picture case ("draws a tilted line and an arched heading …") checks only `data-band`
   polygons; run it and the scanned-report PDF case locally.
5. Docs: `docs/specs/document.md`, `docs/tests/document.md`, this increment's verification.
6. Screenshots of the result in the browser for the product owner.

## Gates

`web`: format, lint, typecheck, i18n audit, knip, unit tests; the changed Playwright cases locally, the full
suite on CI.
