# Verification: Search precise highlights

## Unit tests (Vitest)

- `preview-highlight.test.ts`: `markQuery` (phrase first, lone words only without the phrase, whole words, folding
  only for a question without diacritics, `within`), `matchKind`, `sentenceAround` (terminators, decimals, line
  breaks, block starts, span edges, the 320-character cut).
- `search-presentation.test.ts`: phrase and word snippet marks, an accent-free question, the `Section:` line
  removed, `queryTerms`, `sectionMatchKind`, `sectionPlace` (matching chunk's page, last heading, sheet, a section
  without an entry for its matching chunk).
- `citation-highlight.test.ts`: marks and focus only inside a placed passage, lone words, no sentence across
  blocks, no marks without a question.
- `file-preview.test.tsx`: the focus, word and passage highlights a searched text original registers. The
  "reports each citation" case now renders Markdown: on plain text the highlighter swaps the text after it
  loads and the passages are placed again, so the call count depended on how fast the highlighter loaded.
- `pdf-text-marks.test.ts`: text items placed through the viewport, the phrase across two runs as one rectangle,
  the sentence across wrapped lines, a sentence held to its recorded block, scaling, lone words, a match by
  meaning and a scan.

## Browser tests (Playwright, changed and neighbouring specs only)

Run on this machine against a freshly started fixture server, with a local, uncommitted config that starts the
server with the absolute `node` path (the machine's pnpm shim cannot run `pnpm dev:e2e`) and turns off video
(closing a recorded context hung here). The full suite runs on CI.

| Spec (`tests/e2e/search.spec.ts`) | Result |
| --- | --- |
| marks the question and its sentence inside a PDF's cited region, and outlines a match by meaning (new) | passed |
| marks the words of the question inside the located passages of a text original (updated) | passed |
| steps between the cited passages of a PDF original from the reader's own toolbar | passed |
| searches merged sections, filters, pages and opens each best match with escaped text | passed |
| shows source type, provider and authors, links to Google Drive and outlines the matched PDF region | passed after the fallback below |
| opens a scanned report at its cited page without reading the rest of it | passed after the fallback below |

The last two first failed: their fixtures give a section a provenance entry only for a chunk other than the
matching one, and the reader then had no region. A section without an entry for its matching chunk now keeps
all of its provenance (`matchingChunkProvenance`).

After `PageCitations` was split out of `pdf-view.tsx`, the three PDF specs were run again: the first two passed,
and the new one reported `testEnd` with status `passed` (6.5 s). On this machine the runner then hung while
closing the browser, with the worker process gone and about 0.2 GB of memory free; the hang is after the result
and not in the test.

## Gates

`oxfmt --check`, `oxlint --deny-warnings`, `tsc -b`, the i18n audit and `knip` pass. The full Vitest suite passed
(206 files, 1,169 tests) before the last two edits (the header lines of joined chunks and the Markdown ink); the
search and Markdown tests were run again after them. `check:routes` (a production build) is left to CI.

## Local review (2026-11-10)

The web app ran against `pnpm mock-api` with a local, uncommitted Search sample: a two-page Vietnamese PDF printed
by Chromium with each block's box measured from its layout, a Markdown original, and sections that join
consecutive chunks as the backend does. Screenshots, dark and light, for the product owner are outside the
repository in `C:\Users\Admin\Desktop\plan\search-highlight-screens\`:

1. Results for "nghỉ phép": badges, page and heading, phrase and word marks.
2. The PDF's best match: two sentences of Điều 4 focused, the phrase marked, the margin bar; Điều 5, the next
   chunk of the same section, is no longer marked.
3. The partial match: "phép" underlined in its sentence.
4. The match by meaning: a dashed outline, nothing marked.
5. The Markdown original: the focus sentence with the phrase over it, dotted hints under the other passages.
6. "nghi phep" typed without accents: the same marks as "nghỉ phép".

The review found that a Markdown original's text was invisible in the dark theme: the sheet is always white and
the text used the theme's ink. `MarkdownView` now uses the document ink, as the Word reader does.

A related match first led with a coloured dot, and its dot, page chip and text were not on one line; they are
centred on one line now (measured: the three centres coincide).

## Pull request #911

- The first CI run failed at `oxfmt --check` on `search-result-card.tsx`, formatted in a follow-up commit.
- CodeRabbit (one requested review, on `12b5493`) raised four findings, all valid and fixed: the related-match dot
  showed its kind by colour alone in a text token, so it is now the badge's icon in the `-strong` token;
  `sentenceAround` spread every block start into `Math.max`, which throws past the engine's argument limit, so it
  loops; `queryTerms` split a question pasted with combining marks, so it composes first; `sectionPlace` took the
  first chunk's heading for a joined section, so it reads the matching chunk's and names none when the chunks
  cannot be told apart. Its docstring-coverage pre-merge note was not adopted: the repository has no such rule.

Open: staging.
