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
- [x] `source-catalog-page`: provider cards that say what each type connects.
- [x] Update the list unit tests and the Playwright spec that walks the catalog. The 45 Sources Playwright tests pass after the MEM-105 merge.

### Task 3: Creation flows

- [x] FILE: drop zone, name, access as RadioGroup cards, upload progress; the one-step rail is gone.
- [x] Drive: the credential dialog on shadcn `Dialog`, access as the same cards, credential hints as `Tooltip`. Creation keeps the link field: discovery runs only after the Source exists, so the two-pane tree (4b) belongs to the selection panel on the Source detail page.
- [x] Keep every existing validation and permission rule; only presentation changes.

### Task 4: Source detail shell

- [ ] Breadcrumb, status and access badges, action menu.
- [ ] Tabs for overview, files, sync history, settings.
- [ ] Summary panel carrying access, groups, schedule and credential.

### Task 5: Files and indexing

- [ ] Files tab: status tabs with counts, Table rows, per-row reindex in a menu, progress line while indexing.
- [ ] Index attempts: Tabs plus Table, replacing the `<details>` disclosure and the hand-written table.
- [ ] Index attempt detail in a Sheet: status, timeline, translated error code, reindex.

### Task 6: Sync history and settings

- [ ] Run history Table with status filter.
- [ ] Run detail Sheet: identifiers, start, end, duration, counters, errors.
- [ ] Settings tab: access, groups, schedule, pause switch, AlertDialog confirmations.
- [ ] Credentials card: reconnect and replace-OAuth-app flows.

### Task 7: Verification

- [ ] `pnpm check` and the affected Playwright suites.
- [ ] Orca captures of every screen in the map at desktop and 390px, placed beside their references in `verification.md`.
- [ ] Update the browser section of the connector spec; open the PR; update Linear MEM-106.
