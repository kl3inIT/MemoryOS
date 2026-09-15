# MEM-106 — implementation plan

**Goal:** ship the shadcn foundation, then redesign every Sources screen against its chosen reference. Only the Sources area changes.

**Design:** [design.md](design.md). **Linear:** [MEM-106](https://linear.app/memory-os/issue/MEM-106).

Each task ends with `pnpm check` and a commit scoped to that concern.

- [x] Branch `nhuxuanviet/mem-106-sources-shadcn` from the MEM-105 head.

### Task 1: Primitives

- [x] Add the missing components through the shadcn CLI: dialog, alert-dialog, sheet, tabs, table, dropdown-menu, tooltip, alert, progress, scroll-area, breadcrumb, radio-group, checkbox, switch, textarea, label, collapsible, toggle-group, select.
- [x] Confirm the added components read the existing tokens; the repository already defines every shadcn colour variable, including the dark theme.
- [x] Keep the `tone × prominence` button and adapt the three generated components that assume shadcn's button API.
- [x] The Radix select lives in `ui/radix-select` for Sources; main's native `ui/select` is untouched.
- [x] Teach the test setup about Radix popups, and run `pnpm check`.
- [x] Merge main (2026-09-15): keep main's token-bound `checkbox`, `label`, `table` and `tooltip`, keep both jsdom stubs (ResizeObserver from main, pointer capture from here), and restore every file outside Sources to main.

### Task 2: Sources list and type picker

- [x] `sources-page`: one table with a search field, Provider, Status and Access filter menus, an overview of counts, status and access badges, and `ui/empty` states. TanStack Table is not used, since nothing sorts or pages yet.
- [x] `source-catalog-page`: kept as it was before MEM-106. Per-card descriptions, icon tiles and a Set up link were tried and removed at the owner's request: the page carried too many subtitles.
- [x] Update the list unit tests and the Playwright spec that walks the catalog. The 45 Sources Playwright tests pass after the MEM-105 merge.

### Task 3: Creation flows

- [x] FILE: drop zone, name, Visibility, upload progress; the one-step rail is gone. The first pass used RadioGroup access cards and an Access groups disclosure; the owner found the form crowded, so Visibility is now a Radix `Select` whose options carry a one-line description (Cohere "Create a connector"), and Access groups follow WRITER "Share playbook" and Onyx `AccessTypeGroupSelector`: a chip field with a searchable `Command` list, shown only for Private and always for scoped managers.
- [x] Drive: the credential dialog on shadcn `Dialog`, the same Visibility dropdown and Access groups field (shown for Private, or for a scoped manager's Auto Sync, which the API requires Groups for), credential hints as `Tooltip`. The connector step lost its explanatory paragraphs; the credential line is just the account. Scope is a plain stacked radio list, "Selected files and folders" or "Entire My Drive", with the chosen option's description beneath it, as in Pipedrive's synced folders, Airtable's fields to sync and Klaviyo's sync settings; the bordered Specific/General boxes are gone. Creation keeps the link field: discovery runs only after the Source exists, so the two-pane tree (4b) belongs to the selection panel on the Source detail page.
- [x] Keep every existing validation and permission rule; only presentation changes.

### Task 4: Source detail shell

- [x] Breadcrumb, status and access badges, action menu. Rename and visibility open a shadcn `Dialog` with the Visibility dropdown from creation; deletion keeps its confirmation.
- [x] Tabs for overview, files, sync history, settings. Built on shadcn `Tabs`: FILE has Files, Indexing history and Groups; Drive keeps Content, Sync history and Connection and settings. The summary stays above the tabs rather than behind an Overview tab.
- [x] Summary panel carrying access, groups, schedule and credential. It says who can read in words, lists the associated groups from the groups query cache and keeps the Drive interval and pause state; status and access moved to the header badges. The credential stays in the Credentials card (Task 6).

### Task 5: Files and indexing

- [ ] Files tab: status tabs with counts, Table rows, per-row reindex in a menu, progress line while indexing. Done: each file row has an actions menu (Reindex, Remove with its confirmation) that a pending action blocks, and the upload bar is the shadcn `Progress`. Open: status tabs with counts need a status filter and per-status counts on `GET /api/sources/{sourceId}/items`, which pages by cursor only, so counts from one page would mislead; an indeterminate progress line has no honest value to show, so the Work pending label and row statuses stay.
- [x] Index attempts: Tabs plus Table, replacing the `<details>` disclosure and the hand-written table. They load when the Indexing history tab opens.
- [x] Index attempt detail in a Sheet: status, timeline, translated error code, reindex. The Sheet shows status, duration, the queued/started/completed timeline, the translated error, error code and attempt ID. Reindex is not offered there: `SourceIndexAttempt` carries no item identity, so a failed attempt points to Reindex in the Files tab.

### Task 6: Sync history and settings

- [x] Run history Table with status filter. The filter is the Sources list filter menu, shared as `SourceFilterMenu`, and passes `status` to `GET /api/sources/{sourceId}/runs`.
- [x] Run detail Sheet: identifiers, start, end, duration, counters, errors. The inline list-detail pane is gone. Both Sheets open without a `SheetTrigger`, so they return focus to the View details control themselves; Radix would otherwise focus the page body.
- [x] Settings tab: access, groups, schedule, pause switch, AlertDialog confirmations. Access is changed from the header dialog and Groups keep their section; the pause is a `Switch` beside the Automatic synchronization state in the summary, replacing the Pause/Resume button; credential confirmations stay on the AlertDialog-based `ConfirmDialog`.
- [x] Credentials card: reconnect and replace-OAuth-app flows. Both flows keep their rules. The disclosure's Manage connection/Close label and chevron now follow the Radix `data-state`; `group-open` never matched the Collapsible, so the label never changed.

### Task 7: Verification

- [x] `pnpm check` and the affected Playwright suites. `pnpm check` passes (54 files, 295 unit tests, routes and build); the Sources Playwright suites pass 45/45, plus the credentials disclosure scenario.
- [ ] Orca captures of every screen in the map at desktop and 390px, placed beside their references in `verification.md`.
- [ ] Update the browser section of the connector spec; open the PR; update Linear MEM-106. The spec section is updated.
