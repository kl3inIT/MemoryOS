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

- [x] `sources-page`: kept as the grouped list from before MEM-106, at the owner's request. Each provider is a collapsible group with its Source count, active and public counts and indexed documents above a table of name, last indexed, status, access and documents, with search, Collapse all and a Status/Access filter panel on the Radix `Select`. A single flat table with an overview and filter menus was tried and removed: the owner found it less clear than the grouped list. Status and access badges are pills outlined in their own tone, following Onyx's connector list: Indexing blue, Active green, Failed red, Scheduled neutral; workspace-wide access green, Private amber, Auto Sync blue. Onyx's purple Scheduled has no status token, so it stays neutral.
- [x] `source-catalog-page`: kept as it was before MEM-106. Per-card descriptions, icon tiles and a Set up link were tried and removed at the owner's request: the page carried too many subtitles.
- [x] Update the list unit tests and the Playwright spec that walks the catalog. The 45 Sources Playwright tests pass after the MEM-105 merge.

### Task 3: Creation flows

- [x] FILE: drop zone, name, Visibility, upload progress; the one-step rail is gone. The first pass used RadioGroup access cards and an Access groups disclosure; the owner found the form crowded, so Visibility is now a Radix `Select` whose options carry a one-line description (Cohere "Create a connector"), and Access groups follow WRITER "Share playbook" and Onyx `AccessTypeGroupSelector`: a chip field with a searchable `Command` list, shown only for Private and always for scoped managers.
- [x] Drive: the credential dialog on shadcn `Dialog`, the same Visibility dropdown and Access groups field (shown for Private, or for a scoped manager's Auto Sync, which the API requires Groups for), credential hints as `Tooltip`. The connector step lost its explanatory paragraphs; the credential line is just the account. Scope is a plain stacked radio list, "Selected files and folders" or "Entire My Drive", with the chosen option's description beneath it, as in Pipedrive's synced folders, Airtable's fields to sync and Klaviyo's sync settings; the bordered Specific/General boxes are gone. The creation forms use two type sizes: the form heading, and 14px for everything else, with field labels at the shadcn `Label` scale (`text-sm font-medium`) instead of the 12px bold `font-secondary-action`. Scope is a field label with its help beside it, not a second heading, following Cohere's connector form; its descriptions are one short sentence. Creation keeps the link field: discovery runs only after the Source exists, so the two-pane tree (4b) belongs to the selection panel on the Source detail page. The setup page follows Vanta's Connect AWS and Databricks' Connect data source via partner: the credential step is one bordered section with Create New beside its heading, and the connector step has Connection, Source settings and Content sections, Connection showing the connected account as a single row — provider tile, "Google Drive", the chosen credential with its account, and a Connected / Not connected pill — as in Lindy's Connections, folk's Accounts and Coda. The sidebar already lists the Credential and Connector steps, so the page adds no second stepper.
- [x] Keep every existing validation and permission rule; only presentation changes.

### Task 4: Source detail shell

- [x] Breadcrumb, status and access badges, action menu. Rename and visibility open a shadcn `Dialog` with the Visibility dropdown from creation; deletion keeps its confirmation. A Drive Source's error appears once: Google configuration errors in the Synchronization section, file errors on their own rows. The Source-level file error line is not shown for Drive, since it named no file and could not scale to thousands of files.
- [x] Status and access badges are Onyx's connector pills: white text on a solid fill of the tone. In the dark theme the fill and outline are the Tailwind shades Onyx uses (`--status-*-emphasis` and `--status-*-emphasis-border`: red-900 in red-700, green-900 in green-600, yellow-700 in yellow-600), since the near-black status surfaces read as dull.
- [x] Tabs for overview, files, sync history, settings. Built on shadcn `Tabs`: FILE has Files, Indexing history and Groups; Drive keeps Content, Sync history and Connection and settings. The summary stays above the tabs rather than behind an Overview tab.
- [x] Summary panel carrying access, groups, schedule and credential. Counts and schedule come first and who can read sits in a quieter row beneath, as in Customer.io's integration details: documents indexed, last success and, for Drive, one Automatic synchronization cell holding the pause switch, "Every 30 minutes" (the default for new Sources since V64; existing Sources keep their interval) and the interval editor. Who can read is a short phrase with the full rule in its help; groups come from the groups query cache. A Drive card carries Synchronization as its header, with Refresh status as an icon button beside Synchronize now, as Render places Manual sync; status and access stay in the header badges. The credential stays in the Credentials card (Task 6).

### Task 5: Files and indexing

- [ ] Files tab: status tabs with counts, Table rows, per-row reindex in a menu, progress line while indexing. Done: each file row has an actions menu (Reindex, Remove with its confirmation) that a pending action blocks, and the upload bar is the shadcn `Progress`. Open: status tabs with counts need a status filter and per-status counts on `GET /api/sources/{sourceId}/items`, which pages by cursor only, so counts from one page would mislead; an indeterminate progress line has no honest value to show, so the Work pending label and row statuses stay.
- [x] File icons per extension from vscode-icons, with the Google Docs, Sheets and Slides brand marks from simple-icons; Material's theme icons read as foreign in the table. The icon data is bundled, so it renders on the first paint.
- [x] Large Drive trees. Each folder, and the root list, shows one page of 25 items with Previous/Next and its item range, replacing Load more, whose rows accumulated. The tree keeps open folders and branch pages by path, so paging away and back restores them, and a pending Select for sync returns focus to the item's control after its rows were replaced. Rows follow Databricks Catalog Explorer: no dividers, a hover highlight, a rotating chevron and indent guides. The search results use the same pager. Unsupported and unavailable items are labelled on their rows. Open: a filter such as "unsupported only" needs the item status stored at discovery; it is computed from the Drive listing on each request today.
- [x] Index attempts: Tabs plus Table, replacing the `<details>` disclosure and the hand-written table. They load when the Indexing history tab opens.
- [x] Index attempt detail in a Sheet: status, timeline, translated error code, reindex. The Sheet shows status, duration, the queued/started/completed timeline, the translated error, error code and attempt ID. Reindex is not offered there: `SourceIndexAttempt` carries no item identity, so a failed attempt points to Reindex in the Files tab.

### Task 6: Sync history and settings

- [x] Run history Table with status filter. The owner found the first table unhelpful, so the history opens with the latest (or current) and last successful runs, and each row shows when it started (relative within a week, the local time beneath), its outcome, its trigger (automatic schedule, manual, initial), duration and a one-line activity summary (checked, indexed, removed, pending, failed); the whole row opens its details. The Status filter is a Stripe-style chip, `SourceFilterMenu`, with its own clear button. At the owner's request it takes several statuses: the menu's checkbox items stay open while ticking, and the choices go to `GET /api/sources/{sourceId}/runs` as repeated `status` parameters, which the API now accepts as a list (`run_state IN (…)`, with the sorted set bound into the cursor scope).
- [x] Run detail Sheet in the layout of StackAI's Run Details: Overview (run ID, trigger, start, end, duration, next retry), Stages (read content, index content, each with its duration and state) and File counts with their definitions in a help popover, then errors. The inline list-detail pane is gone. Both Sheets open without a `SheetTrigger`, so they return focus to the View details control themselves; Radix would otherwise focus the page body.
- [x] Settings tab: access, groups, schedule, pause switch, AlertDialog confirmations. Access is changed from the header dialog and Groups keep their section; the pause is a `Switch` beside the Automatic synchronization state in the summary, replacing the Pause/Resume button; the automatic interval is a value and a unit (minutes, hours, days, weeks), as in n8n's schedule trigger and Okta's flow schedule, shown in its largest whole unit while the API keeps whole minutes; credential confirmations stay on the AlertDialog-based `ConfirmDialog`.
- [x] Credentials card: reconnect and replace-OAuth-app flows. Both flows keep their rules. The disclosure's Manage connection/Close label and chevron now follow the Radix `data-state`; `group-open` never matched the Collapsible, so the label never changed.

### Task 7: Verification

- [x] `pnpm check` and the affected Playwright suites. `pnpm check` passes (54 files, 295 unit tests, routes and build); the Sources Playwright suites pass 45/45, plus the credentials disclosure scenario.
- [ ] Orca captures of every screen in the map at desktop and 390px, placed beside their references in `verification.md`.
- [ ] Update the browser section of the connector spec; open the PR; update Linear MEM-106. The spec section is updated.
