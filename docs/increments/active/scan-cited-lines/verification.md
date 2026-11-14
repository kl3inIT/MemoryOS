# Verification: a scan citation marks the question in its line

## Backend (JUnit)

- `PaddleOcrVlClientTest`: the lines request asks `promptLabel: "spotting"` with layout detection off, the
  preprocessor and visualisation off, and no layout option (seven fields).
- `PaddleOcrVlDocumentTest`: spotted lines of a picture go to the text block holding their centre, in the picture's
  pixels; the running header, a table's cell, an empty, three-cornered, off-page or flat line are left out. On an
  upright PDF page, a spotting answer read at twice the layout's size is placed in the same user space, a line goes
  to the smaller of two nested blocks, and a line inside a tilted block's box but outside its outline is not its. Answers
  without lines, read by the preprocessor, without a size or for another number of pages locate none.
- `PaddleOcrVlRoutingTest`: a Source scan is sent twice (layout, then spotting) and records `lines=spotting-v1`; a
  TIFF of one and two frames, twice per document; a Chat scan once, without lines; a spotting answer of 503 leaves
  the document read without lines, each request sent once.
- `StructuredDocumentChunkerTest`: a passage merging two paragraphs carries both blocks' lines in block order, a
  heading its own, a table's rows none, and the provenance is unchanged.
- `SearchIndexWorkIntegrationTest` (PostgreSQL and Flyway through Testcontainers, V201 applied): a passage's lines are
  stored beside its provenance, the chunks read back equal, one passage's lines are read by ordinal, an entry without
  a box is left out, another passage, ordinal or generation reads none. 14 of 14 passed.
- `DocumentSearchServiceTest`: the lines reader needs `SEARCH_READ`, a readable Document and its served generation,
  refuses an ordinal of −1, and reads nothing without the grant.
- `OpenApiContractTest`: the hand-inserted path and schemas match the live API (the generated file reordered some
  3,900 lines, so only the new operation and its two schemas were inserted into the committed contract).

## Web (Vitest)

- `scan-line-marks.test.ts`: where the question lies in each line (the phrase with its offsets, a phrase split over
  two lines, lone words when the phrase is nowhere, a question without diacritics, nothing without a question); its
  share along the line from word spans, or from the characters when the spans do not match; the slab of a tilted
  quadrilateral; a box's lines by page, frame and centre, and its marked lines with their slabs; a stroke along a
  marked line is its focus and its neighbours faint, a straight stroke whose rounded ends reach past the
  quadrilateral still its line's (see the browser review); `narrowRegion`'s outcomes.
- `ink-outline.test.ts` (`wordSpans`): the four words of a straight line and of a line tilted 20 degrees are found
  between the widest spaces of their ink, to within two pixels; asking for six words, or reading a blank line, gives
  none.
- `citation-region.test.tsx`: the whole question follows its line's stroke cut to its slab in the highlighter's darker
  step, without an underline; a lone word without a stroke is its slab in the highlighter, underlined; the focus
  stroke at half, the others under a third; a faint region.
- `image-view.test.tsx`: in jsdom (no pixels) the line is drawn as its quadrilateral, the question over its last 13 of
  21 characters by share, underlined, and the other block faint; without a question the regions are drawn as
  recorded.
- `passage-lines.test.ts`: the answer keeps only lines a viewer can place.
- Preview and documents suites: 25 files, 185 tests passed. `search-page.test.tsx` timed out at 5 s on this machine
  (2 GB free) in cases unrelated to the change and passed 19 of 19 with a 30 s timeout.

## Browser (Playwright, changed and neighbouring cases)

Run locally with an uncommitted config (absolute `node`, no video, no trace); each case reported `testEnd` with
`passed`, after which the runner hung while closing, as on every local run here, and was stopped.

| Case (`tests/e2e/search.spec.ts`) | Result |
| --- | --- |
| marks the question in its line on a picture, the line lighter and the citation's other line faint (new: the tilted line is the focus, "Doanh thu" marked over its first letters and not its last) | passed |
| marks the question in its line on a scanned PDF page, the line lighter and the other block faint (new; the scanned-report fixture drawn without its text layer; the question over the line's end, underlined) | passed |
| draws a tilted line and an arched heading of a picture around their letters, inside the OCR's outline | passed |
| marks the cited region on an image original, through zoom and a turn, and only on the picture it was measured in | passed on the second run; the first timed out in `page.goto` on a cold dev server |
| opens a scanned report at its cited page without reading the rest of it | passed |
| shows source type, provider and authors, links to Google Drive and outlines the matched PDF region | passed |
| steps between the cited passages of a PDF original from the reader's own toolbar | passed |
| marks the question and its sentence inside a PDF's cited region, and outlines a match by meaning | passed |

## Browser review (2026-11-10)

The app ran against the local mock API (uncommitted), whose scanned page answers lines measured from its own ink.
The first look showed the tilted line marked by its quadrilateral over a faint stroke: the stroke was matched by its
corners, and a straight stroke has two corners on each long edge and sixteen in its rounded ends, which reach past
the OCR's quadrilateral. Strokes are now sampled evenly along their outline; the tilted line's stroke is then marked
itself. Screenshots for the product owner, outside the repository, in `C:\Users\Admin\Desktop\plan\scan-cited-lines\`:
"doanh thu thuần" on the tilted line (dark), and "đoạn văn thẳng" on the paragraph's middle line (dark and light).

Before the pull request (2026-11-11), the whole of `tests/e2e/search.spec.ts` ran once more on the final code: 14 of
14 cases reported `passed`.

## Second review (2026-11-10): the question itself

The product owner found the whole line marked and asked for the question itself. The question's words are now found
along the line from the ink's spaces and marked over the line's lighter stroke. Headless Chromium screenshots of the
mock app (light and dark) showed "đoạn văn thẳng," on the paragraph's middle line, "Doanh thu thuần" at the start of
the tilted line, and "kiểm thử" both along the arched heading and in the paragraph, each word for word, the comma
included as the OCR's text holds it. The first build underlined every mark along the quadrilateral's foot, which on
the arched heading ran straight under the hollow, away from the letters; a mark that follows a stroke is now not
underlined. The four picture and PDF cases above passed again after the change.

## Pull request #913

CI passed on `1a6980548`. CodeRabbit (one requested review) raised four findings:

- *A PDF line's frame arrives as null.* Right, and a real defect: the API writes an absent frame as `null`, and
  `scanLines` took any value other than `undefined` for a frame, so every line of an upright PDF page was dropped and
  a scanned PDF's question was never marked. The browser case had answered without the keys, so it passed. Fixed in
  `passage-lines.ts`; `passage-lines.test.ts` and the scanned PDF case now answer `null` as the API does, and the
  PDF and picture cases passed again.
- *The lines request has no bound of its own.* Right: a lines request took the layout's 30 minutes and held a permit,
  delaying the document and other scans for a mark that is only an addition. `lines-timeout` (ten minutes by
  default, at most an hour) now bounds it from when it is asked for, the wait for a permit included;
  `PaddleOcrVlClientTest` shows a lines request giving up unsent while a layout request holds the only permit, and
  `PaddleOcrVlRoutingTest` the default, the binding and the bounds. All PaddleOCR-VL, probe and reader tests pass.
- *Stored lines that are not JSON give a 500.* Right: `readTree` was outside the guard; such a value now reads as no
  lines, and `SearchIndexWorkIntegrationTest` stores one (left to CI here, Docker being stopped).
- *Log when a passage's lines are cut at 64 KiB.* Not taken: a 768-token passage holds some 40 lines, about 6 KiB,
  so the bound is a guard that real passages do not reach; answered in its thread.

## Gates

`oxfmt --check`, `oxlint --deny-warnings`, `tsc -b`, the i18n audit and `knip` pass. `ModulithArchitectureTest`,
`CoreDependencyRulesTest`, `ExtractionDependencyRulesTest`, `DocumentChunkServiceTest`, `ExtractionArtifactReaderTest`
and all 199 extraction tests pass. `ExtractionArtifactLifecycleTest` could not start its PostgreSQL container: Docker
Desktop had stopped on this machine (under 1 GB free) after `SearchIndexWorkIntegrationTest` had run on it. Not run
locally: that class, the full `clean check` and the Playwright suite beyond `search.spec.ts`, left to CI.

## Not verified

- PaddleOCR-VL-1.6's spotting answer on the serving node: it is reachable only from the application node. The request
  and the answer's shape are taken from the PaddleX v3.6.1 source; staging shows whether the answer has lines and
  what a page costs.
