# Source list in pages

## Problem

The administration Sources page loads every Source with `GET /api/sources` and searches, filters, counts and groups
them in the browser, one table section per provider. With hundreds of Sources the page draws every row, an opened
group scrolls for screens, and the group headings are what an administrator reads instead of the Sources. A row also
leaves out what the summary already carries: who manages the Source, why it failed and whether it is still working.

## Reference

Onyx's *Connector Indexing Status* (cloud, read on 2026-11-16 with 34 connectors): one flat list of every type, the
type's icon before each name, sorted by status (indexing, then queued, then indexed), a name search and a status
filter with a count beside each status, and 20 connectors a page under "1~20 of 34 connectors" with previous and next.
Each row also names its creator and a line under the status ("Running", "1st in queue", "5 minutes ago"). Its chart
and cross-connector activity feed are left out: they need run aggregates the list does not, and each Source already
shows its own run history.

## Decision

- **A paged operation for the administration page.** `GET /api/sources/page` (`listSourcePage`) takes `search`,
  `status`, `type`, `access`, `page` (from 0) and `size` (1–100, default 20) and answers `SourcePage`: the rows, `page`,
  `size`, `totalItems`, `totalPages` and `counts`. It follows `listUsers` and its `UserPage`.
- **`GET /api/sources` stays as it is.** The group Source picker (`group-sources-draft.ts`) needs every Source the
  actor may read, and the administration overview prefetches it. The paged operation has its own consumer, so it is
  not a speculative surface ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).
- **Counts come from the server, over what the actor may read.** `counts` holds the total, the count of each status,
  the documents of every Source and whether any Source has pending work, all under the same read scope
  (`SourceScopeSql.READ` and the global flags) as the rows and without the search and filters, so the tiles and the
  status counts do not change as the person filters and never count a Source the actor may not read. The page polls
  quickly while `counts.pendingWork` is true, wherever the busy Source sits.
- **Order.** Failed first, then indexing and pausing, then the rest; within a rank by name, then by id so pages never
  overlap.
- **Search matches the Source name.** The browser search today also matched the translated provider name; SQL cannot
  translate it, and the type filter covers it.
- **Flat rows.** No provider groups. A row shows the provider's icon before the name with the manager's name under it,
  the status with a line under it (the failure reason from `errorCode` through the existing catalog messages, "Still
  working" while `pendingWork`, otherwise when it last indexed, relative), access, documents and the manage action.
- **Address.** `page` joins the search and filters already kept in the address; changing the search or a filter goes
  back to the first page.
- **The member library keeps its list.** `readable-sources.tsx` lists the Sources a member may read from
  `listChatLibrarySources` in the browser. `SourceList` becomes presentational (rows, optional counts and an optional
  pager), so each page supplies its own data and the library behaves as before, now flat as well.

## Out of scope

- The latest run's progress in a row (processed, rate) and an expandable row with that run: a later step that adds a
  latest-run summary to the page rows.
- A chart or an activity feed across Sources.

## Risks

- The status expression is computed per row; counting and ordering by it over every readable Source must stay fast. It
  is the same expression the list already evaluates for every row today.
- The group tone and separator added in `ee3b87994` serve grouped tables only; they leave with the groups.
