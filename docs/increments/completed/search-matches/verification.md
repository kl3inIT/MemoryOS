# Verification

Run on 2026-10-13 on the development machine (Windows 11, no Docker), on branch
`phamnhatanh811/search-matches` rebuilt on `main` at `a5ef3c6c8` and rebased afterwards onto `a5c70cd8c`, which adds one
documentation commit. Every web tool was run from `web/` through
`node`, because `pnpm <script>` does not start on this machine.

## Run here

| Command or scenario | Result |
| --- | --- |
| `git diff --stat origin/main -- memoryos-core memoryos-api memoryos-worker memoryos-extraction openapi.yml web/src/lib/hey-api` | Prints nothing: the backend, the contract and the generated client are those of `main`, so no Gradle run was needed |
| `tsc -b` | No error |
| `oxlint --deny-warnings .` | No finding |
| `oxfmt --check .` | 994 files in the correct format |
| `scripts/i18n-audit.mjs --check` | 0 findings |
| `knip` | No finding |
| `vitest run --maxWorkers=1` | 178 files, 936 tests: 934 passed and 2 failed, neither in a file this increment touches (below) |
| `vite build`, `assert-font-assets.mjs`, `assert-bundle-budget.mjs` | Built; 24 font assets verified; initial load 317.4 of 323.2 KiB gzip; `routeTree.gen.ts` unchanged |
| `search.spec.ts` in Chromium against the production build behind the nginx policy headers, on a private preview server (port 4374, one worker) | 10 passed, the two new cases among them |

The two unit failures, both in files identical to `main`:

- `document-sets-page.test.tsx` ("pages through the sets on the server, one page at a time") failed after
  6.3 s in the full run, which took 20 minutes on a loaded machine, and passed each time it was run alone.
- `chat-web.test.tsx` ("offers Web to a tool-capable model only, never guessing from its name") is
  intermittent on this machine with or without this increment: run alone it failed 5 of 10 times on this
  branch and 3 of 7 times on `main` at `a5ef3c6c8`, each time within 100 ms on the English sentence it looks
  for. Neither failure was retried into a pass and counted; both are reported as they ran.

The build prints one more warning of a kind it already printed twice: the CSS optimizer does not know the
`::highlight()` pseudo-element. The rule reaches the browser, as the browser cases show.

## What the browser cases establish

**"steps between the cited passages of a PDF original from the reader's own toolbar"**: a 12-page PDF with
three matches, on page 3 and on two regions of page 7 a screen apart. The reader has one toolbar; a step goes
to page 7 and its region, the next to the second region of the same page, and a step back returns to the
first.

**The anchor effect of `pdf-view.tsx`** follows the first cited region of the anchor page as a string of its
four coordinates, so it runs when another passage is cited and not when the dialog repaints. Two mutations of
that dependency were run against this case and each failed it, then the committed code passed again:

- Without the dependency the second region of page 7 is not scrolled to (`toBeInViewport` fails after the
  step to "3 / 3").
- Depending on the `boxes` array instead, which is rebuilt on every repaint, the reader who had wheeled 900 px
  away is pulled back when the dialog repaints (the recorded scroll offset changes).

The repaint in the case is a real one: the third passage's text is held back by the fixture and released
after the reader has scrolled away, so the rail fills in around the same active passage. The case then waits
half a second and requires the scroll offset to be exactly what it was.

**"marks the words of the question inside the located passages of a text original"**: a plain-text original
whose heading also holds the words. After the syntax highlighter has replaced the plain rendering, the
registered highlights are read from `CSS.highlights`: the active passage, the other passage, and the terms
`nghỉ phép` and `nghỉ` inside them and nowhere else; they stay when the other passage becomes the one read.

**The source view's lost highlight** was reproduced at the component boundary: with the repair switched off,
`file-preview.test.tsx` ("keeps a passage marked in source…") reads an empty string from the range that had
held the passage.

## The term mark against the passage highlight

The mark uses existing tokens: `--pdf-highlight-border` (`#d99a00`) as its background and
`--content-document` as its ink, with an underline. No token was added. Both tokens are fixed across themes
by design, as the highlighter they belong to is.

Checked on screenshots of the dialog taken through the same harness, in the light theme and in the dark one
(set through the stored theme preference), for a plain-text and a Markdown original; the screenshots are not
committed.

- Against the active passage (`#ffd24d`) the mark's background has a contrast of 1.7:1, and 2.1:1 against an
  inactive passage on the white sheet. That is a visible darker step and not enough alone, which is why the
  mark is also underlined.
- The document's ink on the mark is 8.6:1. In the dark theme the source view's own text is light; the mark's
  words switch to the document's ink and stay legible.
- In all four screenshots the marked words read as distinct from the passage around them, and the passage as
  distinct from the page.

The screenshots were taken before the branch was rebuilt on `main`; the stylesheet rule and the preview kit
are the same files.

## Not run here

- **The repository's Playwright configuration** and the specs other than `search.spec.ts`: port 4173 belongs
  to another session. The deployment-policy fixture of `search.spec.ts` (CSP violations fail a case) ran under
  the private server's nginx headers.
- **`clean check`**: needs containers, and nothing it builds is changed here.
- **Staging**: nothing is deployed.

## Remaining risk

- The same-title finding is by reading the query and the merge; no index was queried.
- The contrast figures are computed from the token values, the legibility judged on four screenshots; no
  person with a colour-vision deficiency reviewed them.
- Found while taking the screenshots and left as they are, because they are not this increment's:
  - A Markdown original in the dark theme sets `text-content-primary` on the white document sheet, so text
    outside a highlight is light on white.
  - The passage highlight in the source view lays the fixed yellow under the theme's light text in the dark
    theme, which reads poorly.
- A CSV renders only the rows in view, so a passage outside them is reported as not found and is not placed
  when it scrolls in. That was so before this increment and is unchanged.
- A step to a PDF passage with no recorded page goes to page 1, as its rail card already did.
