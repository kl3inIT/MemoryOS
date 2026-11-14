# Plan

## Steps

1. **The algorithm** — `web/src/features/preview/ink-outline.ts`: `inkLines` on an RGBA array and an outline,
   the fallbacks of the design, and `inkLinesFrom` reading a drawn source under the outline's bounds.
2. **The region draws bands** — `CitationRegion` takes the lines as fractions of its rectangle and draws one
   polygon per line.
3. **The viewers** — `ImageView` after the picture loads, the PDF reader after a page is drawn; both key the
   result by box and keep the outline until it arrives or when it fails.
4. **Documentation** — `docs/specs/document.md`, `docs/tests/document.md`, the roadmap row.

## Verification

| What | Where |
| --- | --- |
| A tilted band, an arc with gaps between its letters and three lines of a paragraph give one band per line around their ink, inside the outline; a blank region, a dark region, a low-contrast region and too many lines give none | `ink-outline.test.ts` |
| A region with lines draws one polygon per line | `image-view.test.tsx` |
| A picture's tilted line and arched heading are drawn as bands around their letters in a browser, the arch's hollow and the outline's margin unmarked | `search.spec.ts`, with `tests/fixtures/citation-outlines.png` |
| The gates | `pnpm --dir web check` (CI) |

## Open

- Staging: the same page as citation-polygons, opened from Search after the deployment; each band on its line.
- A noisy or coloured scan from the customer's documents, to see how often the fallbacks fire.
