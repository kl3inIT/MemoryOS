# MEM-106 — implementation plan

**Goal:** ship the shadcn foundation, then redesign every Sources screen against its chosen reference, then sweep the rest of `web/src`.

**Design:** [design.md](design.md). **Linear:** [MEM-106](https://linear.app/memory-os/issue/MEM-106).

Each task ends with `pnpm check` and a commit scoped to that concern.

- [x] Branch `nhuxuanviet/mem-106-sources-shadcn` from the MEM-105 head.

### Task 1: Primitives

- [ ] Add the missing components through the shadcn CLI: dialog, alert-dialog, sheet, tabs, table, dropdown-menu, tooltip, alert, progress, scroll-area, breadcrumb, radio-group, checkbox, switch, textarea, label, collapsible, toggle-group, select.
- [ ] Re-point each added component at the existing tokens; no shadcn default colour survives.
- [ ] Rebuild `ui/button` internals on the shadcn recipe, keeping the `tone × prominence` API.
- [ ] Replace the native `ui/select` with the Radix-backed component behind the same props, and fix the call sites.
- [ ] Unit test the token binding and the button variants; run `pnpm check`.

### Task 2: Sources list and type picker

- [ ] `sources-page`: DataTable with a search field and filters for type, status and access; badges for status and access; empty and error states from `ui/empty`.
- [ ] `source-catalog-page`: reference-style provider cards.
- [ ] Update the list unit tests and the Playwright spec that walks the catalog.

### Task 3: Creation flows

- [ ] FILE: drop zone, name, access as RadioGroup cards, upload progress.
- [ ] Drive: credential, scope, access and group steps, with the two-pane selection tree and a footer selection count.
- [ ] Keep every existing validation and permission rule; only presentation changes.

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

### Task 7: The rest of `web/src`

- [ ] Replace bare Radix, `<details>`, hand-written tables and progress bars, native radio/checkbox/textarea and `title=` tooltips across the other features.
- [ ] Record any deliberate exception in the design.

### Task 8: Verification

- [ ] `pnpm check` and the affected Playwright suites.
- [ ] Orca captures of every screen in the map at desktop and 390px, placed beside their references in `verification.md`.
- [ ] Update the browser section of the connector spec; open the PR; update Linear MEM-106.
