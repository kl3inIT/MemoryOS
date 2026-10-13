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

## Staging, 2026-10-13

After the deployment of this increment, nine generated scans were uploaded to a private FILE Source on
`staging.vadan.app` by a group manager, read by the staging OCR (PaddleOCR-VL) and opened from Search in
Chromium. Every scan is one known Vietnamese page (a title, three paragraphs, a four-row table, a note) with its
own code word and figures, drawn at 150 dpi and wrapped in a PDF without a text layer.

**Pages turned by `/Rotate`.** One PDF of three pages, each stored turned and set upright by `/Rotate` 90, 180
and 270. All three were read and indexed; opened from Search, each page is shown upright and its cited regions
(the title, each paragraph, the table, the note) sit on their blocks. The rail reads "Đã đánh dấu". This is
item 4 of the list below, for all three angles.

**What the OCR reads of a damaged scan.** Eight single pages, one damaged in each way. Words of the known page
found in the indexed text, with their diacritics and with diacritics removed; the figures are the page's two
amounts:

| Scan | Words, exact | Words, diacritics removed | Figures |
| --- | --- | --- | --- |
| Upright (control) | 97.4% | 100% | right |
| Crooked by 5° | 97.4% | 100% | right |
| Crooked by 15° | 96.7% | 100% | right |
| Sideways (the picture turned 90°, no `/Rotate`) | 98.7% | 99.3% | right |
| Upside down (the picture turned 180°, no `/Rotate`) | 96.1% | 98.7% | right |
| A curved page | 98.0% | 100% | right |
| Undulating lines | 98.7% | 100% | right |
| A soft, grey, keystoned photograph | 96.7% | 100% | right |

The misread words are diacritics (`hưởng` as `hướng`, `thử` as `thủ`, `Chỉ tiêu` as `Chi tiêu`), on the upright
control as on the damaged pages; Search still finds them through its folded fields. The upside-down picture is
the one structural loss: its blocks come back in reverse reading order, so the title is last and the passages
before it carry no section heading, and one sentence lost its last word.

**Regions on a crooked and on a curved scan.** Opened from Search, the 15° page and the curved page show
upright rectangles that cover each cited block and nothing of another passage's own area beyond what an upright
rectangle around a slanted block must; they overlap where the slanted blocks' rectangles do. Items 1 and 2 of
the list below, for a scanned PDF.

**Not reached.** No picture was read as an image input: the upload form of a FILE Source accepts only PDF,
DOCX, PPTX, XLSX, CSV, TXT and Markdown (`web/src/features/sources/upload/use-file-source-creation.ts`), so a
picture reaches the OCR only from a connector Source or a Chat attachment, and neither was used. Items 1 to 3
for a picture, the EXIF cases and a Chat citation stay open.

## Not verified

For an image input the coordinate space still rests on reading the pinned PaddleX and OpenCV sources: no
answer for a picture has been observed. A page with `/Rotate` has been (see Staging above). Until the rest of
this list has been run through the staging OCR and opened from Search and from a Chat answer, the increment
is accepted for rotated PDF pages and implemented, not accepted, for pictures:

1. A scan placed a few degrees crooked: an upright box that covers the cited block.
2. A photograph of a curved page: a box around the block's whole area, or no box; never a box on another
   passage.
3. A picture taken sideways and one taken upside down, each with and without an EXIF orientation tag: the box
   on the cited passage of the picture as the browser shows it, or no box.
4. A PDF page with `/Rotate` 90, one with 180 and one with 270: the box on the cited passage of the page as
   displayed. Seen on staging on 2026-10-13, from Search.

Also not verified: the reindex procedure in the runbook on real documents (nothing was reindexed), and the
rail's wording on a rotated PDF page whose framed box the reader refuses (it reports what provenance recorded).
