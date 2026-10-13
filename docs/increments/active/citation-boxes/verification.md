# Verification

Run on the development machine on 2026-10-13, at the increment's last code commit. Docker was not running, so
nothing that starts a container ran, and no OCR service was reachable.

## Run

| Gate | Result |
| --- | --- |
| `:memoryos-extraction:test` (whole module) | 189 tests, 0 failed, 4 skipped (the Docling Serve integration cases, which need `DOCLING_TEST_ENDPOINT`) |
| `:memoryos-core:test` for `StructuredDocumentChunkerTest`, `ExtractionArtifactReaderTest`, `ModulithArchitectureTest`, `CoreDependencyRulesTest` | 33 tests, 0 failed |
| `tsc -b` | no errors |
| Vitest, whole unit suite, one worker | 178 files, 923 tests, 0 failed |
| `oxlint --deny-warnings src tests scripts` | clean |
| `oxfmt --check .` | 994 files in format |
| `i18n-audit --check` | 0 findings |
| knip | clean |
| `vite build`, `assert-font-assets`, `assert-bundle-budget` | built; 24 WOFF2 assets; initial load 317.2 of 323.2 KiB gzip |
| `search.spec.ts` in Chromium against a private preview server of the production build | 8 cases passed, the new image case among them; the same run's `chat-history-search` and `chat-research` cases (5) passed |

The browser case opens a Search result whose original is an 854 × 534 PNG with a recorded region
`[171, 107, 598, 267]` in that frame. It measures the region's box against the picture's box on screen: at
100%, zoomed in, and turned a quarter clockwise (where the picture's top edge is its right edge), each to two
decimals of the picture's size; the rail reads "Highlighted". With the same region recorded in a 534 × 534
frame, nothing is drawn and the rail reads "Not found".

## Not run

| Gate | Why |
| --- | --- |
| `OpenApiContractTest` | It starts the application on Testcontainers; with no Docker it fails loading its context (`DockerMachineClientProviderStrategy`). No file under `memoryos-api`, `openapi.yml` or `web/src/lib/hey-api` changed. Left to CI |
| `clean check`, every other Testcontainers suite | No Docker. Left to CI |
| The full Playwright suite with the repository configuration | Its port belongs to another session on this machine. Left to CI |
| A Chat citation into a picture in a browser | The Chat source panel's path is exercised in jsdom (`evidence-view-switch.test.tsx`); a Playwright case needs the Chat fixture server |

## Not verified

Everything about the coordinate space rests on reading the pinned PaddleX, pypdfium2, PDFium and OpenCV
sources and on two recorded PDF answers; no answer for an image input or for a page with `/Rotate` has been
observed. Until these are run through the staging OCR, indexed into a FILE Source and opened from Search and
from a Chat answer, the increment is implemented, not accepted:

1. A scan placed a few degrees crooked: an upright box that covers the cited block.
2. A photograph of a curved page: a box around the block's whole area, or no box; never a box on another
   passage.
3. A picture taken sideways and one taken upside down, each with and without an EXIF orientation tag: the box
   on the cited passage of the picture as the browser shows it, or no box.
4. A PDF page with `/Rotate` 90, one with 180 and one with 270: the box on the cited passage of the page as
   displayed.

Also not verified: the reindex procedure in the runbook on real documents (nothing was reindexed), and the
rail's wording on a rotated PDF page whose framed box the reader refuses (it reports what provenance recorded).
