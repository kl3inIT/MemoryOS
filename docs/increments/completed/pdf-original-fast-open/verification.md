# Verification

## Staging before the change — 2026-10-13

`Báo cáo tài chính năm 2023.pdf` opened from the Search result for "báo" on `staging.vadan.app`, 15,152,559
bytes, not linearized. The browser's resource timing and the route's response headers showed:

- one whole-file request that pdf.js cancels at its headers, the first megabyte, the last 462 KB, then thirteen
  1 MiB ranges started within 8 ms of each other: the whole file, the last range ending 1.4 s after the first;
- `Accept-Ranges: bytes` and `206` with a correct `Content-Range`, so the server was not the cause;
- `pdf-view-*.js` (633,042 bytes) and `pdf.worker.min-*.mjs` (1,265,413 bytes) with no `Content-Encoding`, as
  every script and stylesheet of the initial load; `index.html` alone was gzip.

## The harness

A synthetic scan of the same shape, behind the production build (`vite build`, served with the nginx policy
headers) and the browser-test fixture backend, opened through the real Search page and preview dialog by
Playwright (Chromium, 1440 × 900):

- **The scan**: 24 pages of A4 at 200 dpi, one grey JPEG a page (about 640 KB), each page's picture, content
  stream and page dictionary in that order, every page listed under the root, classic cross-reference table at
  the end. 15,415,675 bytes.
- **The link**: every original response waits for the server (120 ms or 150 ms) and then its turn on a shared
  line of 88 Mbit/s or 10 Mbit/s. Headers and body arrive together, so a request that could start at another's
  headers starts later here than it would on a real line.
- **Drawn** means the cited page's canvas holds an opaque dark pixel on its middle row, polled every frame
  from the click on the result.

Each figure is the median of three runs, the unchanged build and the changed build taken in turn, with nothing
else running on the machine. An earlier set taken while a type check and a lint ran beside it ranged from 1.5 s
to 6.8 s for the changed build and was discarded.

## The scan

| Line | Cited page | Drawn after, before | Drawn after, now | Bytes read before it is drawn | Requests, before → now |
| --- | --- | --- | --- | --- | --- |
| 88 Mbit/s | 1 | 3,751 ms | 973 ms | 14.7 MB → 1.05 MB | 16 → 26 |
| 88 Mbit/s | 13 | 4,301 ms | 1,025 ms | 14.7 MB → 1.15 MB | 16 → 26 |
| 10 Mbit/s | 1 | 14,708 ms | 1,757 ms | 14.7 MB → 1.05 MB | 16 → 26 |
| 10 Mbit/s | 13 | 14,900 ms | 1,860 ms | 14.7 MB → 1.15 MB | 16 → 26 |

The megabyte before the page is the head (128 KiB), the tail (78 KB), 23 page dictionaries (16 KiB each, two
waves of twelve and eleven) and the page's picture. Once the reader settles, with the cited page's neighbours
and the thumbnails in view drawn, it has read 3.5 MB (page 1) or 4.7 MB (page 13) of 14.7 MB in 30 to 32
requests, against all of it in 16; 8 to 10 canvases are drawn against 18 to 28.

Main-thread tasks longer than 50 ms between the click and that point add up to 433–954 ms before and 0–73 ms
now.

Opening the same document again (cited page 13, single runs):

| Line | Before | Now |
| --- | --- | --- |
| 88 Mbit/s | 3,164–3,716 ms, 16 requests, 14.7 MB | 392–532 ms, 1 request (one byte) |
| 10 Mbit/s | not measured | 467–539 ms, 2 requests, 0.59 MB |

## Other shapes

Single runs on the 10 Mbit/s line, cited page 1 and 150:

| Document | Before | Now |
| --- | --- | --- |
| 300 text pages listed flat, 2.15 MB | 3,685 / 3,545 ms, the whole file in 4 requests | 2,671 / 2,655 ms, the whole file in 4 requests |
| 2,000 text pages under a balanced tree, 18.2 MB | 4,509 / 5,550 ms, 2.16 / 3.16 MB | 1,752 / 1,961 ms, 0.44 / 0.69 MB in 3 / 4 requests |

The 2,000-page document still spends 260–720 ms in long main-thread tasks as it opens, before and after: it
mounts 2,000 page placeholders and their observers at once. Not changed here.

## What a rewritten file would have given

The same scan rewritten by qpdf, read by the unchanged reader on the 10 Mbit/s line, cited page 1 / 13:

| File | Drawn after | Bytes read before it |
| --- | --- | --- |
| As scanned | 14,743 / 15,167 ms | 14.7 MB |
| Linearized | 14,631 / 14,893 ms | 14.7 MB |
| Linearized, with object streams | 14,512 / 14,713 ms | 14.7 MB |
| Object streams only | 3,296 / 3,067 ms | 9.7 / 2.7 MB |

Linearization changes nothing because pdf.js walks the same flat page tree for every page but the first.
Object streams gather the page dictionaries into one place, which helps, but less than reading the file as it
is by planned ranges, and at the cost of a second stored copy of every PDF.

## Where the first 840 ms went

With no network delay, from the click to the first request for the original, on the unchanged build:

| At | What |
| --- | --- |
| 68 ms | the original view's chunk is requested; loaded by 102 ms |
| 371 ms | the PDF reader's chunk is requested: 269 ms after the code it waited for had arrived |
| 416 ms | loaded |
| 689 ms | the worker script is requested: 273 ms later again |
| 841 ms | the whole-file probe |

The two gaps are React holding a resolved lazy boundary until 300 ms after its fallback appeared
(`FALLBACK_THROTTLE_MS` in react-dom 19.3). On the changed build the head is requested at about 60 ms, the
reader's chunk and the worker script at about 30 ms, and the page dictionaries at about 360 ms.

## Compression

Sizes of the built files, raw and at gzip level 6:

| File | Raw | gzip |
| --- | --- | --- |
| `pdf-view-*.js` | 634,837 | 189,607 |
| `pdf.worker.min-*.mjs` | 1,265,413 | 375,374 |
| `index-*.js` | 400,243 | 124,908 |

`scripts/nginx-config.test.mjs` holds the configuration; the real nginx is exercised by the "Verify the frontend
image" step in CI, not on the development machine.

## Suites

- `vitest run`: 176 files, 911 tests pass.
- Type check, Oxlint with warnings denied, oxfmt, the i18n audit and knip are clean; the initial load is
  318.2 KiB gzip of the 323.2 KiB budget.
- Against the production build with the nginx policy headers, served on a private port because the suite's own
  was held by another session: `search.spec.ts` 7 of 7; `chat.spec.ts`,
  `chat-generated-file-preview.spec.ts`, `library-hub.spec.ts`, `meetings.spec.ts` and
  `chat-sources-toolbar.spec.ts` 51 of 52. The failure, `chat.spec.ts` "keeps the new conversation mounted
  through server ID promotion and resets only when switching", fails the same way against the unchanged
  build. The other specs were not run.
- The new `search.spec.ts` case fails against the unchanged build at its first assertion, the whole-file read.
- Its last step, a range that fails once the document is open, fails against a build without the reset in
  `readWhole`: the page number stays where the reader was sent, because the observers are still on the canvas
  the whole read replaced.

## Staging, 2026-10-13

After deployment, scanned PDFs of 200 to 650 KB opened from Search on `staging.vadan.app` were read by two
requests, `bytes=0-131071` and the rest of the file, and never whole. The 15 MB report was not measured: the
account used could not read it.

## Not verified

- The 15 MB staging file after deployment.
- The nginx change on a real nginx outside CI.
- The reverse proxy passing `Accept-Encoding` through and the compressed response back, which only the
  deployment shows.
