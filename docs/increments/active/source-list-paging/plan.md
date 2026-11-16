# Plan

Owner decision (2026-11-16): follow Onyx's list (flat rows, type icon, status order and counts, 20 a page) and keep a
table; a row shows the manager, the failure reason and the relative time. Latest-run progress is a later step.

## Steps

1. **Core.**
   - `SourcePageQuery` (search, status, type, access, page, size) and `SourcePage` with `SourceCounts` in the
     `connector` API package, beside `SourceSummary`.
   - `SourceManagementService.listSourcePage(actorId, query)` under the existing read authority.
   - `JdbcSourceQueryRepository.page(...)`: the summary select in a CTE under `SourceScopeSql.READ`, filtered, ordered
     by status rank, name and id, `LIMIT`/`OFFSET`; counts over the unfiltered readable set in one query.
2. **API.** `GET /api/sources/page` (`listSourcePage`) with `SourcePageResponse` and `SourceCountsResponse` under
   `api/source/contract/`; regenerate `openapi.yml` and the Hey API client.
3. **Web.**
   - `SourceList` presentational: flat rows with the provider icon, manager line and status line; optional pager.
   - `sources-page.tsx` reads `listSourcePage` with the address's search, filters and `page`; tiles and status counts
     from `counts`; fast polling while `counts.pendingWork`.
   - `readable-sources.tsx` keeps its client-side list.
   - Remove the `group` row tone and the multi-body rule from `table.tsx`.
4. **Docs.** `docs/specs/connector.md` (the list operation and order), `docs/tests/connector.md`, the roadmap row.

## Tests

- Core: the page query's scope (a scoped manager's page and counts hold only their Sources), order, filters, search,
  paging bounds and counts.
- API: `SourceApiIntegrationTest` for the operation and its validation; `OpenApiContractTest` after regeneration.
- Web: `source-list` and `sources-page` unit tests; the library readable-sources tests stay green.

## Verification

| What | Result |
| --- | --- |
| Pending | |
