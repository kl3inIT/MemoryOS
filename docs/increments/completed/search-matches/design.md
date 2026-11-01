# Search matches

## Problem

A Search result opens its document with the located passage painted as one block. The words the reader asked
for are marked in the result card's excerpt and nowhere in the document they open. A PDF original has no
previous/next control: the text formats and workbooks step between passages from the floating toolbar, a PDF
only from the rail.

## What exists

- **Retrieval.** `OpenSearchIndexService.searchPrepared` sends one hybrid query (`multi_match` over
  `title^2`, `title.folded^2`, `content`, `content.folded`, and a radial k-NN above the semantic floor) with
  `size` and `pagination_depth` equal to `candidate-limit` (500 chunks). `DocumentSearchService.search` groups
  the readable hits by Document in score order, merges each group's consecutive ordinals into sections and
  keeps the best three.
- **Card.** `search-result-card.tsx` renders the sections: the first as the best match, the others as compact
  related lines. `createSearchSnippet` marks the query's terms in the excerpt.
- **Dialog.** `search-page.tsx` `openDocument` maps every section to a `DocumentSelection.matches` entry.
  `useDocumentReading` reads each match's text from its own passage window. `CitationRail` lists the matches
  under "*n* đoạn khớp nhất".
- **Highlight.** `HighlightedText` in `file-preview.tsx` wraps the Word, CSV, Markdown, source and plain-text
  views: `placeCitations` flattens the rendered text, `normalizeForMatch` (NFC per grapheme cluster, case,
  whitespace, soft hyphens) and `locatePassage` place each passage, `paintCitations` registers two CSS Custom
  Highlights, and `CitationSteps` floats the previous/next control. A workbook is placed by its recorded row
  and has the same steps. A PDF is `LazyPdfView` with the active match's `pages` and `boxes` and its own
  toolbar (page field, return to the citation, zoom); selecting a rail card changes those props and the
  reader scrolls to the new anchor page.

## Decisions

### The query's terms inside the located passage

One matcher marks terms everywhere. `preview-highlight.ts` gains `matchTerms(text, terms, within?)`: the
occurrences of each term in a `NormalizedText`, longest term first, as source offsets. It compares under
`normalizeForMatch`, the rule the passage locator already uses, so capitals, combining marks, soft hyphens and
line breaks inside a phrase do not hide a word.

- **The original.** `placeCitations` already holds the normalized text of the rendered view and each placed
  passage's span. It ranges the terms inside the placed spans, and `paintCitations` registers them as a third
  CSS Custom Highlight, `memoryos-citation-term`, with a higher priority than the two passage highlights so it
  shows on top of both. It is `--pdf-highlight-border` under `--content-document` ink, underlined: the darker
  step alone is 1.7:1 against the active passage, which is not enough to carry the difference. Nothing is
  marked outside a located passage: a common word marked across a whole document is noise, and an unlocated
  passage draws nothing, as before.
- **The card.** `highlightLiteralTerms` and the snippet's anchor use the same `matchTerms` instead of their
  own regular expression, so the card and the document mark the same words. The card gains what the locator
  already had (a word typed precomposed matches text stored with combining marks); it loses nothing.
- **Which terms.** `highlightTerms(query)` in `search-presentation.ts`, as before: the whole query and its
  tokens of two characters or more. The Search page passes them in `DocumentSelection.terms`; the preview kit
  takes a list of strings and knows nothing of Search. A Chat citation sets none and is unchanged.

Accents are not stripped: "nghi phep" finds "nghỉ phép" on the server through the `folded` analyzer and marks
nothing in either place, as on the card before. Marking by a looser rule than the reader typed would mark
"phép" for "phẹp". A semantic-only hit contains none of the words and gets the passage highlight alone.

A workbook stays at row level and a PDF at block level: neither is located by text.

Source and plain text needed one repair first. `TextView` draws the text plain and swaps in the syntax
highlighter's markup when it has loaded; the ranges placed in the plain rendering then stood on text that had
left the document, so the passage highlight vanished a moment after the file opened. `HighlightedText` watches
such a view and places the passages again when a placed range has collapsed. It is limited to the view that
swaps its text: a CSV renders only the rows in view, and placing again on every scroll there would change what
the rail reports.

*Rejected: `<mark>` elements in the rendered original.* The Custom Highlight API exists in this kit so that
`docx-preview`'s layout and `sanitizeDocxHtml`'s guarantees are untouched.

### Steps for a PDF

The step control's two buttons and counter leave `CitationSteps` as `CitationStepControls`, which
`CitationSteps` keeps rendering in its floating toolbar. For a PDF, `FilePreview` passes the same controls to
`PdfView` through a `steps` slot, rendered as one more group of the toolbar the PDF reader already has. A
second floating toolbar would sit on top of the first.

Stepping calls `onActive`, exactly as a rail card does: the dialog selects the match, the PDF receives that
match's `pages` and `boxes`, and the existing effect scrolls to the new anchor page and draws its box. Two
matches on one page do not move the anchor, so that effect also follows the first cited region of the anchor
page as a string of its four coordinates. The value changes only when another passage is cited, so a repaint
of the dialog moves no reader who has scrolled elsewhere. The edit to `pdf-view.tsx` is the slot prop, its
place in the toolbar and that dependency; the box-drawing block is not touched.

## Designed, built and dropped: the count and the list of ten

The first form of this increment also made a result say how many passages of its document matched and list the
best ten: `sectionCount` and ten `sections` on the result, `candidateLimitReached` on the page, a
"*n* đoạn khớp khác" line on the card, and a rail that led with three matches and revealed the rest. It was
implemented and verified, and the product owner dropped it on 2026-10-13: Search stays at three passages a
document, and an explanation of why Search ranks rather than lists is enough. The work is kept on the local
branch `phamnhatanh811/search-matches-with-ten` and is no part of what ships; the Search API, its OpenAPI
document and the generated client are unchanged by this increment.

What a reader who wants every occurrence needs is a find inside the open original, which is recorded as a
candidate in the [roadmap](../../../roadmap.md#other-tracked-work).

## Same-title documents and the pool

**Finding: the defect is real by construction; it was not reproduced against a running index.**

1. Every chunk carries its document's title twice. `OpenSearchIndexService.write` stores `title` on each chunk
   (`source.put("title", document.title())`), and `StructuredDocumentChunker.prefix` starts each chunk's
   `content` with `Title: <title>`, which is also what is embedded.
2. The keyword clause is `multi_match` (best fields, any term) over `title^2`, `title.folded^2`, `content`,
   `content.folded`. A query that matches a title therefore matches every chunk of that document, and all of
   them receive the same title score: the field is identical on each.
3. A document may hold 10,000 chunks (`StructuredDocumentChunker.MAX_CHUNKS`); the corpus has documents of 909
   and 1,100 chunks ([MEM-248](https://linear.app/memory-os/issue/MEM-248),
   [MEM-249](https://linear.app/memory-os/issue/MEM-249)). The keyword clause contributes at most
   `pagination_depth` = 500 hits and the response holds 500.

So one document of 500 chunks or more whose title matches the query can occupy the whole keyword side with
chunks that tie on the title score, and which tied chunks make the cut is index order, not relevance. A second
document with the same title, or any document that matches in its body, then reaches the pool only through the
semantic clause, where the long document's chunks also carry the title in their embedded text. Grouping by
Document happens after the cut, so the result list can be one document where the corpus holds several.

No test covers it: `OpenSearchRetrievalIntegrationTest` indexes single-chunk documents with a candidate limit
of 50, and `DocumentSearchServiceTest` replaces the index with a mock. The document-viewer increment recorded
the same cause from the other side ("a query hitting a section heading ranks every cell under that heading").

**Proposed, not implemented; recorded as a candidate in the [roadmap](../../../roadmap.md#other-tracked-work)
(owner decision 2026-10-13: no query change in this increment).** Bound each document's share of the pool in
the index query instead of after it: `collapse` on `document_id` with `inner_hits` in the hybrid query, so the
500 candidates are 500 documents with their best passages. It needs proof against the pinned OpenSearch 3.8.0
before anything else: that a hybrid query accepts `collapse` with `inner_hits`, how the inner hits are scored
and ordered, and that paging depth still holds. That is an integration test on a real index, and a change to
ranking and to the query.

## Excluded

- Any change to the Search API, ranking, the query or the index mapping.
- Term marks in a PDF or a workbook.
- Accent-insensitive term marks.
- A find across the whole original.

## Boundaries

- No backend change and no contract change: nothing under `memoryos-*`, `openapi.yml` or the generated client
  differs from `main`.
- No new dependency and no new token. The Custom Highlight API is a browser primitive already used here.
- New web code stays out of the entry chunk: the matcher is a module of the readers' chunk that the Search
  route also imports.
- Shared with the citation-boxes increment: `pdf-view.tsx` (toolbar slot and one effect dependency only) and
  `file-preview.tsx` (the PDF case and the step controls).
