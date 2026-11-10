# Search: precise highlights

## Problem

A Search result is marked too widely to tell which passage matched, on the card and in the opened original.

**The card.** `createSearchSnippet` marks the whole question and every word of two characters or more with one
style. For "nghỉ phép", the phrase, the word "nghỉ" in "ngày nghỉ" and the word "phép" alone look the same, so
a passage that holds the exact phrase does not stand out from one that holds a single word, and a semantic-only
hit looks like the others with nothing marked. A passage's page is shown only after the document opens.

**The PDF original.** Opening a match on a PDF paints a yellow band over every line of the whole section:

1. `search-page.tsx` gives the dialog `matchingProvenance(section.provenance, matchingOrdinal)`, the provenance
   of every chunk of the merged section, the matching one first. `readSourceLocation` collects the boxes of all
   of them, so the chunks next to the match are painted as well.
2. A chunk records one box per block (paragraph, list item, table row), and an outlined box is drawn as one
   band per text line ([citation-line-hugging](../citation-line-hugging/design.md)), so one chunk of ten lines
   is ten bands.
3. The PDF reader renders no text layer (`renderTextLayer={false}`) and ignores the question's words
   ([search-matches](../../completed/search-matches/design.md#excluded) excluded term marks in a PDF), so even a
   literal full-text match cannot be marked more precisely than its block.

Text originals (Word, Markdown, plain text, source) already locate the passage in the rendered text and mark the
words inside it, but paint the whole passage as the focus and mark the phrase and lone words alike.

The approved sketch is `C:\Users\Admin\Desktop\plan\search-highlight-de-xuat.html` (2026-11-09), outside the
repository.

## Decisions

### One rule for what a question marks

`preview-highlight.ts` gains `markQuery(text, terms, within?)` over `QueryTerms = { phrase, words }`, used by the
card, the text originals and the PDF reader alike. It replaces `matchTerms`.

- **Phrase first.** When the whole question occurs in the span, only its occurrences are marked (`whole`). Only
  when it does not are the question's words marked, each on its own. A one-word question is its own phrase.
- **Whole words.** An occurrence must start and end at a word boundary (the neighbouring character is not a
  letter, mark or digit), so "an" no longer marks inside "ban".
- **Accents follow the reader.** A question typed without any Vietnamese diacritic is compared with the text's
  diacritics removed (`đ` as `d`), as the index's `folded` analyzer already matched it; a question with any
  diacritic is compared exactly, as before. This reverses the search-matches exclusion "accent-insensitive term
  marks": its concern was marking "phép" for "phẹp", which still cannot happen when the reader typed an accent,
  while "nghi phep" found "nghỉ phép" on the server and marked nothing.
- **Match kind.** `matchKind(text, terms)` is `exact` when the phrase occurs, `partial` when only some words do
  and `none` otherwise. Search shows these as *Khớp chính xác*, *Khớp một phần* and *Liên quan về nghĩa*.
- **The sentence around a mark.** `sentenceAround(text, start, end, within)` widens a mark to its sentence: back to
  the previous `.`, `!`, `?`, `…` or `;` followed by a space, a line break or the start of the span, forward to the
  next terminator or the end of the span, and to at most 320 characters around the mark, cut at word boundaries.
  Folding keeps every character's position, so offsets mean the same text in both comparisons.

### The card

- The best match sits in a quiet panel with its match-kind badge (`MatchKindBadge`, `StatusBadge` tones
  `success`, `warning`, `info`) and its location: the page or sheet from the matching chunk's provenance and the
  last heading of the passage's `Section:` line.
- A phrase is a strong mark (`--pdf-highlight` under document ink); a lone word is underlined in
  `--evidence-highlight-border` without a fill; a `none` passage has no mark.
- Related matches keep one line each, led by a small dot in the badge's tone and the page chip.
- The snippet drops the chunker's `Title:` and `Section:` lines before every chunk the section joins; only the
  first was dropped before, so a merged section showed the second chunk's header in its excerpt. The heading is
  shown in the location chip instead.

### The opened original

- **Only the matching chunk.** The Search page gives each match the provenance of its `matchingOrdinal` chunk
  only (`matchingChunkProvenance`). A section with no provenance entry for its matching chunk keeps all of its
  provenance, as before, rather than opening with no region. Chat keeps `matchingProvenance` unchanged; this
  increment does not change Chat.
- **Text originals.** With `terms`, a placed passage whose text holds marks paints its sentences as the focus
  (`memoryos-citation-focus`, a light fill), the phrase marks strong (`memoryos-citation-term`) and lone words
  underlined (`memoryos-citation-word`). The whole passage is no longer painted. Another passage's marks are a faint
  underline (`memoryos-citation-hint`), with no passage fill. A passage without marks keeps today's passage
  highlight. Without `terms` (Chat) nothing changes.
- **PDF originals.** For each page that holds the active match's boxes, the reader reads the page's text with
  `page.getTextContent()` (no text layer is rendered) and places each text item in rendered pixels through the
  page viewport. Items whose centre lies inside one of the boxes form the passage's text, in content order; a
  change of box or a vertical gap of more than 1.6 lines ends a sentence. `markQuery` and `sentenceAround` run on
  that text, and each span becomes one rectangle per item, sized by the item's width split in proportion to the
  measured width of its characters (canvas `measureText` in the item's font family).
  - Marks found: the sentence rectangles are drawn in the light focus colour, the marks over them (phrase filled,
    word underlined), and the box itself becomes a 3 px bar in the left margin, which keeps the
    `pdf-citation-box` slot, label and scroll target.
  - No mark, but the page has text: the box is drawn as a dashed outline, without fill, because the passage is a
    semantic match whose words cannot be pointed at.
  - No text in the boxes (a scan) or the text could not be read: the box is drawn as today.
  - While the page's text is being read the box is not drawn, so the reader does not see the full box flash and
    shrink.
- **Rail.** A Search citation card shows its match kind beside its page.

Rejected:

- *Rendering pdf.js's text layer.* It mounts a span per text run on every page drawn and needs its stylesheet;
  the marks need positions only, on the cited pages only.
- *OpenSearch `highlight`.* It would report exactly which tokens the lexical clause matched, folded forms
  included, but needs an API and contract change; the client rule above already agrees with the `folded`
  analyzer for the cases readers type.
- *Marking every occurrence across the original.* Still a find inside the document, a roadmap candidate.

### Found while reviewing

A Markdown original's text was invisible in the dark theme: `MarkdownView` draws the white document sheet in
both themes but set the text in the theme's ink. It now uses `--content-document`, as `DocxView` does. This is a
fix to an existing reader, kept in this increment because the review of the text-original marks needs it.

## Boundaries

- Web only. No backend, API, `openapi.yml` or generated-client change.
- No new dependency. New highlight colours reuse `--pdf-highlight`, `--pdf-highlight-border`,
  `--evidence-highlight-border` and `--content-document`.
- Chat citations are unchanged: they pass no `terms`, and Chat keeps the section's provenance.
- Shared with the active citation increments: `pdf-view.tsx` (the box layer of each page) and `citation-region.tsx`
  (two drawing variants). The outline and ink-line drawing of a box drawn as today is untouched.
