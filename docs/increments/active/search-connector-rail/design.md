# Search connector rail

## Context

The Search UI owner asked on 2026-09-14 for a Sources rail beside direct Search results, grouped by connector (uploaded files and Google Drive today; Gmail or Slack only once such connectors exist), each row showing how many results it holds. The same request covers the first per-type viewer step: PDF results open on their pages.

MEM-87 removed the file-type facet rail the same day (see [its design](../../completed/mem-87-citation-evidence-ux/design.md)) because it counted only the ten results on the current page and repeated the file-type menu. This increment is a deliberate departure from the resulting sentence in the [Search contract](../../../specs/search.md). The Search UI owner authorized it; confirmation from the MEM-87 decision owner is still open.

## Decisions

- Rows are connector types (`SourceType`), not individual Sources: `FILE` is shown as uploaded files and `GOOGLE_DRIVE` as Google Drive. Rows follow the web provider catalog order so they do not move when counts change. Connectors without code are not predeclared.
- `POST /api/search` accepts `sourceTypes` (at most ten entries, deduplicated). It narrows results to Documents with at least one actor-readable mapping of a selected type and never widens Source scope.
- `SearchPage.sourceTypeFacets` counts the grouped, authorized candidates per connector after the file-type and time filters and before the connector filter. A Document mapped to two connectors counts in both, so row totals can exceed `totalResults`. With a connector filter, `totalResults` is the filtered count.
- The connector filter applies inside the same bounded candidate set rather than re-querying OpenSearch, so a row's count equals the results it opens. As with `totalResults`, Documents ranked beyond `candidate-limit` are not reached.
- Direct Search now reads actor-readable Source metadata for every grouped candidate (bounded by `candidate-limit`, batched by 1000) instead of for the returned page only. The page reuses that read. MEM-93 measured this read at 400 candidates in `SearchAuthorizationCostMeasurementTest`.
- Layout: at the large breakpoint the rail sits left of the results and the results column keeps its readable width; below it the rail becomes a Sources menu in the filter toolbar. The page never shows both. The rail is shown for every search and lists every catalog connector, disabling those without results: an earlier rule that hid the rail below two connectors made the search box and results jump when a filter left one connector (owner feedback 2026-09-15).
- Single selection, matching the file-type menu; the API keeps a list so multi-select stays possible.
- PDF results with recorded page provenance open on the PDF pages tab; passages stay one click away.

## Out of scope

Text, spreadsheet and presentation viewers (they need headings, block kinds or table cells from the API), Gmail and Slack, rotated-scan highlight correction (`page_orientation` is not in chunk provenance) and per-row table highlights (table rows carry only the whole-table box).

## Verification

`SearchRequestTest` and `DocumentSearchServiceTest` cover validation, counting across all candidates and filtering within them; the OpenAPI contract and generated client are regenerated; web unit tests cover the rail, the narrow menu and the request body; the change is exercised in the local runtime.
