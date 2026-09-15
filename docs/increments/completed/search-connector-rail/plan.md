# Plan

## 0. Scope

- [x] Search UI owner authorized the connector rail and PDF-first viewer step (2026-09-14).
- [ ] MEM-87 decision owner confirms the departure from "no facet rail".

## 1. Backend

- [x] `SearchRequest.sourceTypes` validation (null, duplicates, bound).
- [x] `DocumentSearchService` reads readable metadata for all candidates, counts connectors and filters within the candidates.
- [x] `SearchPage.sourceFacets` (`total` and per-connector counts) and the matching `SearchPageResponse` records.
- [x] Unit tests: `SearchRequestTest`, `DocumentSearchServiceTest`.
- [x] OpenAPI contract regenerated.

## 2. Web

- [x] Hey API client regenerated.
- [x] Connector rail (large breakpoint) and Source menu (narrow), request body, clear filters.
- [x] Search PDF results open on the PDF pages tab; Chat citations keep passages first.
- [x] Translations and unit tests (`search-page.test.tsx`); typecheck, lint, format and i18n audit pass.

## 3. Verification and documents

- [x] `docs/specs/search.md` and `docs/tests/search.md` updated.
- [ ] `clean check`.
- [ ] Local runtime: search, filter by connector, open a PDF result.
- [x] Reconcile the roadmap when the pull request merges (PR #164; roadmap reconciled 2026-09-15).
