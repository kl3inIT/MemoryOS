# Verification: scan citations drawn as highlighter strokes

## Unit tests (Vitest)

- `ink-outline.test.ts`: the existing cases still hold on the new strokes (a tilted line with gaps covered and its
  outline's margin left, an arched heading followed with its hollow left, one stroke per paragraph line, no
  stroke for blank, dark or low-contrast regions or too few points). New: a straight line of accented letters
  with descenders has edges at one height each across the line, above the letters' bodies but below a lone
  accent's tip and above a descender's foot; the three lines of a block, one wearing many accents, share one
  thickness.
- `citation-region.test.tsx`: line strokes carry `data-band`, have no edge class, and sit in a group clipped to
  the OCR's outline.

## Browser tests (Playwright, changed and neighbouring cases only)

Run locally on a fresh fixture server with a local, uncommitted config (absolute `node` path, no video). Each
case reported `testEnd` with status `passed`; the runner then hung while closing the browser, as on every local
run on this machine, and was stopped.

| Case (`tests/e2e/search.spec.ts`) | Result |
| --- | --- |
| draws a tilted line and an arched heading of a picture around their letters, inside the OCR's outline (now reads `polygon[data-band]` only) | passed |
| marks the cited region on an image original, through zoom and a turn, and only on the picture it was measured in | passed |
| opens a scanned report at its cited page without reading the rest of it | passed |

## Gates

`oxfmt --check`, `oxlint --deny-warnings`, `tsc -b`, the i18n audit and `knip` pass.

## Browser review (2026-11-10)

The app ran against the local mock API with two scanned pictures located by PaddleOCR-VL-shaped outlines: the
repository's `citation-outlines.png` and a synthetic page with a three-line Vietnamese paragraph, a line tilted
20 degrees and an arched heading. Screenshots, dark and light, for the product owner are outside the repository
in `C:\Users\Admin\Desktop\plan\scan-highlight\app\`, beside the approved sketch.
