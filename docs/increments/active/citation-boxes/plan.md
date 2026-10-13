# Plan

## Steps

1. **A box may carry its frame** — `ExtractedDocument.BoundingBox` gains optional `frame_width` and
   `frame_height`, omitted when null. Done.
2. **C1, image boxes** — `PaddleOcrVlDocument` keeps an image's boxes in the picture's own pixels when the
   answer says the picture was read as sent; `parser_configuration` records `boxes=displayed-frame-v1`. Done.
3. **C3, rotated pages in the adapter** — boxes of `/Rotate` 90, 180 and 270 pages in points of the displayed
   page; an answer in another frame keeps the page alone. Done.
4. **C3, rotated pages in the viewer** — `readSourceLocation` reads the frame; `pdfBoxRect` places a framed box
   on a page of any rotation and still refuses an unframed one on a rotated page. Done.
5. **C2, the image viewer** — `frameBoxRect`, the highlight layer in `ImageView`, the image case of
   `FilePreview`, the rail's report in `DocumentPreviewDialog`. Done.
6. **C4 and C5, documentation** — the two contracts, the two matrices, the reindex procedure in the
   [extraction runbook](../../../runbooks/docling-extraction.md#documents-read-before-boxes-were-kept-for-images-and-rotated-pages),
   the roadmap row and [verification.md](verification.md). Done.

## Verification

| What | Where |
| --- | --- |
| A framed box survives the artifact and chunk provenance; an unframed one is written as before | `StructuredDocumentChunkerTest`, `ExtractionArtifactReaderTest` |
| An image keeps its boxes in the picture's pixels, per frame; none when the answer does not say the picture was read as sent, outside the picture or without area; an answer without a size is refused | `PaddleOcrVlDocumentTest`, `PaddleOcrVlRoutingTest.aSourceImageIsReadByPaddleOcrVlThroughTheRouter` |
| A turned page keeps its boxes at 90, 180 and 270 in points of the displayed page; the stored page's proportions, a preprocessed answer and a half turn that does not say keep the page alone | `PaddleOcrVlDocumentTest` |
| Provenance with a frame reads as a framed box, a broken frame as none | `source-provenance.test.ts` |
| A framed box is placed on a turned PDF page and refused on a page of other proportions | `pdf-pages.test.ts` |
| A picture's region is drawn in the picture's transform, only on a picture of the recorded size | `image-view.test.tsx`, `file-preview.test.tsx` |
| The Chat source panel's path marks a cited picture | `evidence-view-switch.test.tsx` |
| A Search result's picture is marked in a browser, zoomed and turned, and not when the frame differs | `search.spec.ts`, last case, on a private preview server |

Results and what was not run are in [verification.md](verification.md).

## Open

The increment stays under `active/` until the first item is done.

- **Real samples through the staging OCR**, each indexed into a FILE Source and its citation opened from Search
  and from a Chat answer. What each must show:
  1. A scan placed a few degrees crooked: an upright box that covers the cited block.
  2. A photograph of a curved page: a box around the block's whole area, or no box; never a box on another
     passage.
  3. A picture taken sideways and one taken upside down, each with and without an EXIF orientation tag: the box
     on the cited passage of the picture as the browser shows it, or no box; the upside-down picture with a tag
     is the EXIF half-turn case the size check cannot see.
  4. A PDF page with `/Rotate` 90, one with 180 and one with 270: the box on the cited passage of the page as
     displayed.
  Record one answer of an image and one of a `/Rotate` page as fixtures beside the two existing ones.
- **The rail on a rotated PDF page** reports a citation as located from provenance even if the reader refuses
  its framed box. Reporting what the page drew needs a callback out of `pdf-view.tsx`.
- **A Chat citation into a picture in a browser.** The Chat source panel's path is covered in jsdom; its own
  Playwright case needs the Chat fixture server and belongs with `chat.spec.ts`.
- **Docling's boxes on an image input and on rotated pages** stay undrawn: nothing here establishes their frame.
- **Documents indexed before** are brought up to date by an operator with the runbook procedure; nothing was
  reindexed in this increment.
