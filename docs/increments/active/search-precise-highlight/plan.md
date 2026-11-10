# Plan: Search precise highlights

Design: [design.md](design.md). Branch `phamnhatanh/search-precise-highlight`, web only.

## Steps

1. **Matcher.** `preview-highlight.ts`: `QueryTerms`, `markQuery` (phrase first, whole words, folding when the
   question has no diacritic), `matchKind`, `sentenceAround`; remove `matchTerms`. Unit tests in
   `preview-highlight.test.ts`.
2. **Card.** `search-presentation.ts`: `queryTerms`, snippet parts with `phrase`/`word` tones, section kind and
   location. `MatchKindBadge` in `documents/`. `search-result-card.tsx` per the sketch. Unit tests in
   `search-presentation.test.ts`; `search-page.test.tsx` kept green.
3. **Text originals.** `citation-highlight.ts`: placements carry marks and focus sentences; `paintCitations`
   paints focus, term, word and hint highlights; `base.css` declares them. Tests in `citation-highlight.test.ts`.
4. **PDF originals.** `pdf-text-marks.ts` (pure: items to rendered rectangles, passage text, spans to
   rectangles) with unit tests; `pdf-view.tsx` reads the cited pages' text and draws focus, marks, the margin bar
   or the dashed outline; `citation-region.tsx` variants; `file-preview.tsx` passes `terms`.
5. **Wiring.** `search-page.tsx` sends `queryTerms`, the matching chunk's provenance only and each match's kind;
   `document-preview-dialog.tsx` and `citation-rail.tsx` carry the kind.
6. **Catalogs.** New strings in `app-translations.en.ts`.
7. **Browser tests.** `tests/e2e/search.spec.ts`: update the text-original marks test; add a PDF test that marks
   the phrase and its sentence inside the cited box and falls back to the outline for a semantic match; card
   badges. Run only the changed specs locally; the full suite runs on CI.
8. **Docs.** `docs/specs/search.md` card and preview paragraphs, `docs/tests/search.md` matrix rows.
9. **Local review.** Run the mock API with the Search sample corpus (not committed) and capture screenshots for
   the product owner before any pull request.

## Gates

- `web`: lint, format, typecheck, i18n audit, unit tests (run directly with `node node_modules/...` on this
  machine), then the changed Playwright specs.

## Open

- Product owner review of the local screenshots, then staging.
