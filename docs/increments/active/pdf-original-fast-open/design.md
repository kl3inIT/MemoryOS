# A PDF original opens at its cited page without reading the file

## Problem

Opening a scanned report from Search or a Chat citation on staging is slow, and stays slow every time it is
opened. Measured on 2026-10-13 against `Báo cáo tài chính năm 2023.pdf` (15.15 MB) on staging: one whole-file
probe, the first megabyte, the last, then thirteen megabyte ranges at once, all 15 MB before the first page. A
synthetic scan of the same shape (24 pages, one JPEG a page, 14.7 MB) behind the production build and a
throttled link reproduces the same sixteen requests; the numbers are in [verification.md](verification.md).

The reader was already built to read by HTTP range and to draw only the pages near the viewport. Six things
defeat that:

1. **A flat page tree reads the whole file.** A scanner writes each page's picture, content and page
   dictionary one after another and lists every page directly under the root. pdf.js cannot load such a
   document before it has the first and the last page, and to reach a page it fetches the dictionary of every
   page in parallel (`Catalog.getPageDict`: "launch all requests in parallel … if all the pages are top
   level"). Each dictionary sits in a different 1 MiB chunk, so loading the document costs every chunk.
2. **The page rail draws about 18 pages at once.** Each thumbnail is a full page to pdf.js: its bytes and its
   decode. With the first problem fixed the rail alone would still read most of a scan.
3. **The cited page competes with its neighbours.** Three or four pages are requested with it and share the
   link and the worker.
4. **Everything starts in sequence.** The dialog's code, then the reader's (633 KB), then the worker's
   (1.27 MB), then the probe, the first chunk and the last chunk: 840 ms pass before the first byte is asked
   for with no network delay at all.
5. **React holds each lazy reader back.** `React.lazy` suspends once even for a chunk already in memory, and
   React 19 commits a retry no sooner than 300 ms after the fallback appeared (`FALLBACK_THROTTLE_MS`). The
   original view and the PDF reader inside it are two such boundaries: about 550 ms of the 840 ms above.
6. **Nothing is kept, and the code travels uncompressed.** Closing the dialog and opening another citation of
   the same document reads it again. The web container serves every script and stylesheet as `identity`: the
   reverse proxy in front of it compresses HTML only.

Linearizing the file at ingestion, the usual answer, does not help: pdf.js uses the linearization hint for the
first page only and walks the same flat tree for every other one. The linearized sample read all 14.7 MB in
14.6 s, as the original did. Rewriting it with object streams does help (3.1 s) because the page dictionaries
land together, but it needs a second stored copy of every PDF, its lifecycle and a backfill, and the reader
change below is faster on the file as it is.

## Decision

Keep the original byte for byte and change how the browser reads it. No server contract changes: every range is
still its own authorized read, and originals are still served `no-store`.

### The reader reads through its own range source

`pdf-range-source.ts` replaces pdf.js's URL loader for a same-origin original. It hands pdf.js a
`PDFDataRangeTransport`, the library's documented entry point for a caller that fetches the bytes itself.

- **Small blocks, so a need is stated exactly.** pdf.js is told 16 KiB chunks. Loading a flat document then
  costs 16 KiB a page instead of 1 MiB.
- **Reads asked for together are planned together** (`pdf-range-plan.ts`). pdf.js asks for each range in its
  own message, so asks are gathered for 4 ms, sorted, and neighbours closer than 64 KiB become one request.
  Three or more separate runs are a burst and are fetched exactly. One or two are ordinary reading and are
  extended to 256 KiB, forwards and, where held bytes stop that, backwards: the bytes beside a wanted object
  are usually wanted next and cost less than a round trip. This is what keeps a small block from turning a
  text document into hundreds of requests, and a walk back from the end of a file (cross-reference table,
  catalog, page tree) from costing a request a step.
- **At most twelve reads are in flight**, the most recent ask first. Twelve stays under the API's database pool
  (15), which every range's authorization draws on, and leaves a 24-page scan's opening burst two waves.
- **The file is opened with two reads instead of three.** The first asks for the head (128 KiB) and learns the
  size from `Content-Range`; the tail (64 KiB), where the cross-reference table is, is requested as soon as those
  headers arrive. The whole-file probe pdf.js makes is gone. A file up to 1 MiB is read whole in those two.
- **A failed read falls back to one whole read**, as before.

### What a session read is kept, and authorized again before it is used

The source keeps the blocks it fetched, per original URL (which names the document and its generation), up to
32 MiB in all, least recently used first out, and forgets an original nobody read for ten minutes at a sweep
every ten, so between ten and twenty minutes after its last use. It is memory in the page, never the HTTP
cache (every read asks for `no-store`): a reload or a sign-out, which leaves the page, empties it.

Authority is not kept. Every opening makes one authorized request before a single held byte reaches pdf.js: the
head when nothing is held, one byte otherwise. A refusal, or a size that differs from the held one, drops what
was held. A reader who lost access therefore cannot reopen a document from memory; what they can still see is
what was already on their screen, as today.

### The cited page is drawn first

- Until the cited page is drawn, or the reader scrolls, types or picks a page, it is the only page mounted. Its
  neighbours follow.
- The page rail waits for the document canvas to have nothing left to draw, then draws the thumbnails in view
  one at a time, nearest the current page first. A thumbnail that left the rail's surroundings is released, as
  before.

### The code, the worker and the first bytes start with the dialog

- `loadable` (`web/src/lib/loadable.tsx`) loads a component's chunk on demand like `React.lazy` but never
  suspends: it renders the component at once when the chunk is in memory and a fallback until then, so React's
  300 ms hold does not apply. The original view and the PDF reader use it. A chunk that cannot be loaded is
  thrown to the error boundary, as a lazy component's is.
- `warmOriginal` starts the readers' chunks, preloads the pdf.js worker script and, given the original's
  address, opens it. The preview dialog and the inline evidence tabs call it as they show an original; a
  Search result calls it without an address when it is pointed at or focused, which loads code only. The
  reader claims the opening the dialog started instead of making its own.
- The pdf.js worker is started when the reader mounts and shared by mounted readers, instead of when pdf.js is
  handed a document, so it boots while the first bytes are still on their way.

### Static files are compressed

`web/nginx.conf` turns gzip on in the locations that serve files from disk. The initial load drops from about
1.2 MB to the 318 KiB the bundle budget already counts, the reader's chunk from 635 KB to 190 KB and its worker
from 1.27 MB to 375 KB. Responses proxied from the API are left alone: they carry secrets beside text the caller
chose, and a streamed answer must not wait in a compression buffer. CI checks the built image's real nginx for
it.

## Out of scope

- A second, rewritten copy of each PDF. Measured above; revisit only if large flat scans stay slow in use.
- Server-rendered page images and thumbnails.
- Windowing the page list: a 2,000-page document still mounts 2,000 placeholders and their observers at once.
- Word files, which `docx-preview` still reads whole and lays out in one pass.
- Search matches and highlights, and coordinates for images and rotated pages: the next two increments.

## Reused, and the gaps

- `PDFDataRangeTransport` and the `worker` option of `getDocument` (pdf.js); `react-pdf`'s `Document` accepts
  both and treats each as a distinct resource.
- The existing page windowing (`pagesToRender`), whole-file fallback and e2e range fixtures.
- Gap: pdf.js offers no way to bound, order or coalesce the ranges it asks for, and its chunk size is one
  number for both stating a need and reading ahead. The range source fills only that.
- Gap: `React.lazy` cannot render a loaded chunk without suspending. `loadable` fills only that.
