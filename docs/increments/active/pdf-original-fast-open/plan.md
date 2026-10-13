# Plan

## Steps

1. **Baseline** — the synthetic scan and two text documents behind the production build and a throttled link;
   the staging file's request pattern. Done, in [verification.md](verification.md).
2. **Compression** — gzip in the static locations of `web/nginx.conf`, the configuration test and the image
   check in CI. Done; the real nginx is exercised only by CI.
3. **Range source** — `pdf-range-plan.ts`, `pdf-range-source.ts` and the transport in `pdf-view.tsx`: small
   blocks, planned reads, head and tail opening, the session's kept blocks with an authorized opening, the
   whole-file fallback. Done.
4. **Drawing order** — the cited page alone first, then its neighbours; the page rail one thumbnail at a time
   while the canvas is idle. Done.
5. **Start with the dialog** — `loadable` in place of the two lazy boundaries, `warmOriginal` from the dialog,
   the evidence tabs and a pointed-at Search result, the shared worker started as the reader mounts. Done.
6. **Documentation** — [Document contract](../../../specs/document.md), the Search and Document verification
   matrices, the frontend conventions and the deployment runbook. Done.

## Verification

| What | Where |
| --- | --- |
| Reads asked for together: a burst is fetched exactly, ordinary reading is extended forwards and backwards, neighbours merge, nothing held or on its way is fetched again, the most recent ask is last | `pdf-range-plan.test.ts` |
| Opening reads the head then the tail; a small file whole; a server that ignores the range; an answer that is not the range asked for is refused | `pdf-range-source.test.ts` ("opening an original") |
| An opening started ahead is claimed once; a held original is authorized with one byte; a refusal or a changed size drops what was held | `pdf-range-source.test.ts` ("opening an original") |
| Held bytes answer without a request; an ordinary read serves the next ask; a burst is one request each, newest first; a failed read reports once; a closed reading asks for nothing | `pdf-range-source.test.ts` ("reading an opened original") |
| The oldest blocks are shed first; only whole blocks and a file's last short one are kept | `pdf-range-source.test.ts` ("HeldOriginal") |
| A 24-page scan opens at cited page 13 by range only, starting with its head; the first page's picture is never fetched; under half the file is read once the neighbours and the thumbnails in view are in; the rail draws fewer than twelve thumbnails; opening it again starts with one byte and reads under a tenth of the file | `search.spec.ts` ("opens a scanned report at its cited page without reading the rest of it"); fails on the build before this change |
| A text original still opens at its cited page with no whole read, and falls back to exactly one whole read when ranges fail | `search.spec.ts` (source presentation case), `chat.spec.ts` (citation case) |
| Static locations switch gzip on, the worker's own location too, the script and stylesheet types are covered, and no location that proxies to the API compresses | `scripts/nginx-config.test.mjs` ("nginx compression") |
| The built image's nginx sends its largest script and the pdf.js worker with `Content-Encoding: gzip` | CI, "Verify the frontend image" |

Not verified here: the staging file itself after deployment, which needs a signed-in browser; the nginx change
on a real nginx outside CI (no container runtime on the development machine); a Word file, which this change
does not touch.

## Open

- Measure `Báo cáo tài chính năm 2023.pdf` on staging after deployment and add it to
  [verification.md](verification.md).
- `chat.spec.ts` "keeps the new conversation mounted through server ID promotion…" fails against a privately
  served production build of unchanged `origin/main` as well as of this change (the suite's own port was held by
  another session); not looked into here.
- The tail waits for the head's response headers because the server ignores a suffix range (`bytes=-N`).
  Honouring one would start both at once and save a request's latency on every first opening.
