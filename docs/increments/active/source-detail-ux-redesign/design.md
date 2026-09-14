# Source detail UX redesign — MemoryOS-owned surfaces

> **Superseded in part, 2026-09-14.** Section 1 (ACL inspector) and the ACL half of section 4 no longer apply: the inspector, its Permissions tab and its HTTP endpoints were removed at the user's direction, and collected Google permissions are consumed server-side only. See [MEM-88](../mem-88-google-drive-acl-sync/design.md#acl-inspector-removal--approved-2026-09-14).

## Problem

The Source detail page mixes Onyx-inherited management UI with MemoryOS-owned surfaces that were designed ad hoc: the Google Drive ACL inspector, the sync run history with per-file errors, and the Drive selection tree. These custom surfaces are functional but do not follow proven UX patterns:

- **ACL inspector** (`google-drive-acl-panel.tsx`): a flat file table opens a modal per file. Permission entries are long cards mixing identity, role, expiry, inheritance and provenance. Observation status (missing / failed / stale / successful-empty) is only visible inside the modal, so the list cannot answer "which files have permission problems?".
- **Run history** (`source-run-history.tsx`): every run looks identical until "View details" opens a modal; per-file errors live only inside that modal. There is no status legend, so outcome badges (e.g. `SUPERSEDED`, `SOME_ERRORS`) are unexplained — the Confluence audit-log pattern solves exactly this.
- **Drive selection panel** (`google-drive-selection-panel.tsx` + `google-drive-selection-tree.tsx`): the "Selected content" section carries a 5-paragraph HelpPopover, five-plus stacked async states (pending validation, recovering, conflicted, uncertain, recovery error, status unavailable), two hidden draft modes (link textarea vs linked-document approval checkboxes), and the primary "Edit selection" action inside a collapsed `<details>`. The tree and the filtered flat list are two different mental models in one panel. Live check: tree rows are ~64px tall with two lines of redundant text ("Tệp · Trong phạm vi" under every name), and the search form occupies a full row above the tree even when unused.
- **Tab structure** (`source-detail-page.tsx`): Content / Permissions / History / Settings tabs exist, but the ACL panel and run history each reinvent their own search, pagination and detail-modal idioms instead of sharing one list+detail pattern.

## References (Mobbin)

- Confluence audit log — status legend under the table, "Show more" per row: https://mobbin.com/screens/cc682da6-c0ab-4bfc-bbc6-9fbb17f4a01b
- Braintrust logs — list + persistent detail side panel (not modal): https://mobbin.com/screens/b2e50d75-4499-46cb-8d61-db349117e9f2
- Vercel deployment — run detail as a page section with inline log lines, errors highlighted: https://mobbin.com/screens/50a79ff4-37c0-4a9b-8df3-29599652a9fb
- Dropbox Dash share screen — people list with role dropdowns: https://mobbin.com/screens/8a353f34-8f05-4af0-9a63-1a9070931822


## Proposed changes

### 1. ACL inspector → list + side panel

Replace the modal with a two-pane layout inside the Permissions tab (Braintrust pattern): file list left, selected file's permission detail right (stacked on narrow widths). The list gains a per-file observation status column (Observed / Failed / Stale / Not yet observed) so problems are visible without opening detail. Permission entries become compact rows — identity + role badge + expiry — with inheritance/provenance behind one `<details>` per entry, unchanged semantics. The current modal is confirmed oversized relative to its content (verified live: ~90% viewport for a 4-entry list).

### 2. Run history → status legend + inline-expandable errors

Add a collapsible status legend under the run table (Confluence pattern, verified: "What do the different statuses mean?" disclosure listing each badge with a one-line explanation) explaining each outcome badge. Inside run details, per-file errors render as expandable rows: filename + error summary line, expanding to provider-error guidance, error code, operation/run IDs and current item status. No data changes — same retained counters and error fields, different presentation.
### 3. Drive selection → explicit View/Edit modes + selection summary

Add a persistent summary bar above the tree: "N folders · M files · K linked documents" plus an Edit action, so the active selection is visible without expanding nodes. Make the two modes explicit: View shows the tree and search read-only; Edit shows a prominent draft bar ("Editing selection — N changes", Save/Cancel always visible) instead of hiding draft actions inside `<details>`. Consolidate the stacked async states into a single status banner (pending → succeeded/failed). Move "File and folder links" editing out of the collapsed details into a clearly headed edit section. Compact tree rows to a single line (icon + name + status chip) — the current two-line "Tệp · Trong phạm vi" subtitle repeats on every row.


### 4. Shared idioms

One shared list+detail layout and one expandable-row component reused by both the ACL panel and run history; keep existing `StatusBadge`, `TablePagination`, `SettingsLayout` and theme tokens. No new dependencies.

## Non-goals

- No change to Onyx-inherited source list, upload flow, or backend APIs.
- No document-read enforcement, no editing Google sharing — inspector stays read-only.
- No Pause/Resume UI (recorded as future work in MEM-88).

## Verification

- Existing tests updated where presentation changes break them; no new test files unless a genuinely uncertain edge appears.
- Browser verification at desktop and narrow widths via the real local instance, per the MEM-88 verification approach.
