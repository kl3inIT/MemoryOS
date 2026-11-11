# A scan citation marks the question in its line

## Problem

Search marks a text PDF's citation by the sentence and the phrase of the question
([search-precise-highlight](../search-precise-highlight/design.md)). A citation into a scan or a picture still marks
every line of every cited block, now as highlighter strokes ([scan-highlighter-bands](../scan-highlighter-bands/design.md)):
on a paragraph of eight lines the reader still has to find the keyword. On 2026-11-10 the product owner asked to narrow
a scan's mark to the line that holds the question, from line positions the OCR gives (option B), not from an estimate
the viewer makes from the block's text and its ink (option A). Reviewing the first local build, which marked the
whole line, they asked for the question itself to be marked, as a text original marks it.

PaddleOCR-VL's layout request answers, per block, its text (`block_content`), its box and its outline. It answers no
line position, and `block_content` keeps no reliable line break.

## Evidence

PaddleX v3.6.1 source, the version the serving node runs (read 2026-11-10):

- `/layout-parsing` takes `useLayoutDetection: false` with `promptLabel: "spotting"` (`schemas/paddleocr_vl.py`
  allows `ocr`, `formula`, `table`, `chart`, `seal`, `spotting`). The pipeline then reads each page as one block
  labelled `spotting` with the prompt `Spotting:`; a page under 1,500 px on both sides is doubled first; the picture
  is held to 112,896–1,605,632 pixels; `skip_special_tokens=false` is forwarded to the vLLM server (`extra_body`), so
  the location tokens survive.
- `post_process_for_spotting` reads `<|TEXT_START|>…<|TEXT_END|>` and eight `<|LOC_n|>` (0–1000 of the page) per line
  into `spotting_res: {rec_polys: [[[x, y] × 4]], rec_texts: [...]}`, in pixels of the page as read.
  `PaddleOCRVLResult._to_json` writes it into `prunedResult.spotting_res`; serving prunes only `input_path` and
  `page_index`.
- The generation is capped by the pipeline's `max_new_tokens` (4,096), so a very dense page can lose its last lines.
- [citation-line-hugging](../citation-line-hugging/design.md) rejected spotting as "a second full VLM pass per page
  for quadrilaterals that do not follow an arc". That still holds for the *shape*, which the ink bands keep drawing;
  this increment accepts the pass for the *text* of each line, which only the OCR can tell.

## Decision

### Extraction: a second request for a Source scan's lines

- After the layout request, `PaddleOcrVlExtractor` sends the same input again as `PaddleOcrVlClient.Task.LINES`:
  `useLayoutDetection: false`, `promptLabel: "spotting"`, the preprocessor and visualisation off as before. Same
  client, same permits, its own deadline; its logs are `paddleocr_vl.lines.completed` and
  `paddleocr_vl.lines.failed`, and `paddleocr_vl.lines.located` counts the lines kept.
- Only a Source document asks for lines (`OcrReader.read(..., lines)`): Search is the reader that marks a question.
  A Chat attachment and the connection probe keep one request.
- The lines request has its own deadline, `lines-timeout` (ten minutes by default), counted from when the lines are
  asked for with the wait for a permit included, where a layout request waits for its permit untimed: lines held up
  behind a busy service, or slow to come, give way rather than hold the document and the GPU (PR #913 review).
- Lines are an addition. An answer that fails or is malformed leaves the document read without lines; a Worker that
  is stopping still stops. An artifact the lines would carry past its 32 MiB bound is published without them
  (`paddleocr_vl.lines.dropped`).
- `PaddleOcrVlDocument.lines` places each page's quadrilaterals exactly as its blocks' boxes (one `Placement` for
  both): CropBox user space for an upright PDF page, the displayed frame for a turned page or a picture read as sent,
  from the spotting answer's own `width` and `height`. A page whose boxes are not kept keeps no line.
- A line belongs to the smallest text block (paragraph, heading, list item) of its page whose outline, or box, holds
  the line's centre. A line in no text block (a running header, a page number, a table's cell, a picture) is left
  out, as are a line without text, longer than 1,000 characters, with a corner that is not a number or lies more
  than a pixel off the page, or without area. A page keeps at most 500 lines.
- `parser_configuration` gains `;lines=spotting-v1` when lines were asked for.

### Artifact and passages

- `ExtractedDocument.lines`: `[{block, page_no, text, bbox}]`, `block` the block's `index`, `bbox` in the block's
  space with the quadrilateral as `polygon`. Omitted when empty; older artifacts have none.
- The chunker writes, for each passage, the lines of the blocks whose location its provenance cites (every part of
  a split block carries all of that block's lines) as `lines_json`, `[{page_no, text, bbox}]`, at most 64 KiB,
  leading lines kept. Tables carry none.
- V201 adds the nullable `document_chunks.lines_json`. The provenance is unchanged, so Chat's 8,192-character bound,
  the OpenSearch document and the Search answer are unchanged. The chunk convention is unchanged: a document gains
  lines when it is read again.

### API

`GET /api/search/documents/{documentId}/passages/{ordinal}/lines?generation=` (`readSearchPassageLines`) answers
`{lines: [{pageNo, text, coordOrigin, frameWidth?, frameHeight?, points}]}` from PostgreSQL, under the Search
reader's authority: `SEARCH_READ`, a readable Document and the generation it serves. A passage without lines answers
an empty list. Chat has no such endpoint: it marks no question.

### Viewer

- The Search reader asks for the lines of the active citation's matching passage when the original is a PDF or a
  picture, the citation has regions and the search has a question.
- `scan-line-marks.ts` finds where the question lies in each line's text: the phrase, or its words when the phrase is
  nowhere (`markQuery`), the lines read as one text so a phrase broken across two lines has a part in each.
- Where the question's words lie along the line is read from the drawn ink (`wordSpans`): the line's quadrilateral is
  walked from its left side to its right side a pixel at a time, asking whether any ink lies across the line there;
  the widest runs without ink, one fewer than the text has words, are its spaces, and each must be at least an eighth
  of the line's height wide (`MIN_SPACE`, 0.12). The question then runs from the start of its first word to the end
  of its last. When the ink does not show every space, or the pixels cannot be read, the question's share of the
  line's characters is used instead.
- A highlighter stroke belongs to a marked line when more than half of its outline, sampled evenly along its length,
  falls inside the line's quadrilateral widened by a quarter of its height; a neighbouring line's stroke comes that
  close with one edge at most. Three tones, as a text original's focus and term: the question, cut from its line's
  stroke to the slab of the quadrilateral it takes, in the highlighter's darker step (a lone word of the question in
  the highlighter at full strength); its line's stroke lighter (half); the citation's other strokes faint (under a
  third). A marked line no stroke matched, or a region whose pixels cannot be read, draws the line's quadrilateral as
  its line and the slab over it, underlined along the slab's straight foot. When no line holds the question (a match
  by meaning, or no lines), every stroke is drawn as before.
- Text PDFs keep their sentence and phrase marks; the endpoint answers nothing for them.

### Rejected

- *Lines in the chunk's provenance.* A 768-token passage holds some 40 lines; with their text the provenance passes
  the 6,000-character merge bound and Chat's 8,192-character limit, and every search answer grows.
- *Lines in the Search document window.* That window is read from OpenSearch without a PostgreSQL load; a stored
  field changes the index mapping.
- *Reading a page's lines from the artifact per request.* Up to 32 MiB from object storage for each citation opened.
- *Estimating the line in the viewer (option A).* No exact line; the product owner chose B.
- *Marking the whole line only.* The product owner's review (2026-11-10) asked for the question itself, as text
  originals mark it.
- *Placing the question by its share of the line's characters alone.* Off by a character or two on proportional type;
  kept only as the fallback where the ink does not show the spaces.
- *An underline along the quadrilateral's foot on every mark.* On an arched heading the OCR's quadrilateral is the box
  around the arc, so its foot runs straight under the hollow; the mark follows the stroke there instead.

## Cost and rollout

- One more VLM generation per Source page, over the whole page, up to 4,096 tokens. Not measured: the serving node
  is reachable only from the application node. Staging measures it from `paddleocr_vl.lines.completed` `elapsed_ms`
  against `paddleocr_vl.request.completed` before production; the GPU is shared with production.
- Existing scans keep no lines until a Source manager reindexes them ([runbook](../../../runbooks/docling-extraction.md)).

## Boundaries

- No change to provenance, the OpenSearch mapping, the chunk convention or Chat.
- Docling, model and AI Hay readers write no lines.
