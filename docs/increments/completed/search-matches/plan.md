# Plan

Each step is one commit that type-checks and lints on its own.

## Steps

1. **Terms in the passage** — `matchTerms` in `preview-highlight.ts`, term ranges and the third highlight in
   `citation-highlight.ts` and `HighlightedText`, the `::highlight()` rule, the card's highlighter on the same
   matcher, `DocumentSelection.terms`. Done.
2. **Source keeps its highlight** — `HighlightedText` places the passages again when the source view has
   swapped its plain rendering for the highlighted one. Done.
3. **PDF steps** — `CitationStepControls`, the `steps` slot of `PdfView`, the anchor effect following the first
   cited region. Done.
4. **Browser case for the terms** — `search.spec.ts`. Done.
5. **Documentation** — `docs/specs/search.md`, `docs/specs/document.md`, the Document matrix, this increment's
   documents, the roadmap row and two candidates. Done.

The count of matching passages and the list of ten were steps of this plan, were delivered, and were dropped
by the owner on 2026-10-13 ([design](design.md#designed-built-and-dropped-the-count-and-the-list-of-ten)).

## Verification

| What | Where |
| --- | --- |
| Terms are found under the locator's normalization, longest first, without overlap, inside a given span | `preview-highlight.test.ts` |
| Terms are ranged only inside a placed passage, and an unlocated passage has none | `citation-highlight.test.ts` |
| The card's excerpt marks the same words as before, a word stored with combining marks and a phrase broken over two lines | `search-presentation.test.ts` |
| A text original registers the terms of a located passage above both passage highlights | `file-preview.test.tsx` |
| A passage placed in source stays marked once the highlighted rendering has replaced the plain one | `file-preview.test.tsx` |
| A PDF original offers previous/next in its one toolbar, reports the step, and offers none for one passage | `file-preview.test.tsx` |
| In a browser: a PDF stepped to its next cited page and region, to a second region of the same page and back; a reader who scrolled away is not moved by a repaint | `search.spec.ts` |
| In a browser: the terms painted inside the located passages of a plain-text original and nowhere else | `search.spec.ts` |

## Risks

- **`::highlight()` paints few properties.** The term mark is a background, an ink and an underline; the
  contrast and the screenshots are in [verification](verification.md#the-term-mark-against-the-passage-highlight).
- **Two increments edit `pdf-view.tsx` and `file-preview.tsx`.** The edits here stay in the toolbar and one
  effect dependency.

## Staging acceptance (2026-10-19)

"quyền và nghĩa vụ của cổ đông" opens the Savico charter with 49 marked ranges, all inside the located passage. Three other Word originals did not locate their passage ("Không tìm thấy"), which predates this increment.
