# MEM-212 Sources list: readable on a phone, status colour for state only, a failed Source says why

## Requirement

A design critique of the web application on 2026-10-02 (Impeccable `critique`, 24/40) found the Sources list the weakest administration surface. This increment is its first fix batch, frontend only:

1. **P1-1** The Sources table (`/admin` and *Thư viện › Tổ chức › Nguồn dữ liệu*) is `min-w-6xl` inside a horizontal scroller, and the Users table is `min-w-224`. At 390 px only the name and one other column show; status, access and the manage action sit off-screen with no cue. Both must be usable on a phone and at 200 % zoom.
2. **P1-2** Access (*All members*, *Specific groups*, *Sync permissions*) is painted in status tones copied from Onyx: *Specific groups* is the same orange as *Paused*, against the rule that status colours carry state only ([design tokens](../../../guidelines/design-tokens.md)). A status the client does not know renders as *Scheduled*.
3. **P1-3** The same word, *Active*, is a solid pill on Sources and a soft badge on Users.
4. **P2-1** Every provider group repeats a four-figure summary row and a full column header, so three providers show the header three times.
5. **P2-2** A failed list load reads *Sources unavailable*, which sounds as if the Sources themselves are down.

Owner decisions (2026-10-02): access becomes a neutral outline badge with its icon; status badges are soft everywhere.

## Design

### Sources list (`features/sources/source-list.tsx`)

- **One summary, one header.** The four figures (*Total sources*, *Active sources*, *Open to all members*, and the document total named by the page's `documentsLabel`) move into one `StatStrip` above the search, computed over every Source the page lists, as the [composites rule](../../../conventions.md#composites) asks of every figure summary. The shorter labels replace *Workspace-visible sources* and *Total docs indexed*, which a phone's two-column strip truncated; `documentsTotalLabel`, then always equal to `documentsLabel`, is removed. The table gets a single `TableHeader`.
- **Slim group rows.** Each provider stays a collapsible `tbody` whose first row is one `rowgroup` header spanning the table: chevron, provider icon, provider name, then "2 sources · 129 documents" in muted text. The whole row stays a pointer target; the provider button stays the keyboard's way to it and keeps its accessible name.
- **Failures first.** Inside a group, `FAILED` Sources come first; the rest keep the server's order.
- **Two layouts from the list's own width.** The table sits in a CSS container (`@container`). At 56 rem and wider (`@4xl`) it shows every column with a fixed layout. Narrower, it shows *Name*, *Status* and the action; the name cell then carries a second line with the access label, the document count and the last indexed date, and the access cell's extra content (a member's Groups) under it. Nothing scrolls sideways. A container query, not a viewport breakpoint, because the same list sits beside a 15 rem sidebar on the administration page and inside the library's tab on a member's page.

### Status and access badges (`features/sources/shared/`)

- `SourceStatusBadge` uses `StatusBadge`'s default soft variant. `NOT_STARTED` keeps *Scheduled*; a status the client does not know gets its own neutral *Unknown* (the catalog's existing *Chưa rõ*) with a help icon instead of borrowing *Scheduled*.
- A `FAILED` row shows its badge alone. A first version printed `sourceStatusMessage(errorCode)` under the badge; the owner rejected it on 2026-10-02: Onyx's connector table shows the badge only, and the reason already sits on the Source's detail page (`source-files-panel.tsx`, `history/source-item-history.tsx`). The list keeps leading the group with the failure.
- `SourceAccessBadge` becomes the registry `Badge` with `variant="outline"`, its icon and label: access is not a state, so it takes no status tone. The Source detail page uses the same badges and changes with them.

### Load failure copy

`sources-page.tsx` and `readable-sources.tsx` say *Couldn't load sources* (*Không tải được danh sách nguồn*) instead of *Sources unavailable*.

### Users table (`features/users/users-table.tsx`)

Below `md` (`useIsMobile`), the table hides *Groups* and *Account type* through `columnVisibilityFeature`, the way the [data table pattern](../../../conventions.md#frontend-patterns) hides a column shown only sometimes, and drops its `min-w-224`; the status column narrows. Name, status and the row menu stay on screen. Its status badge is already soft.

## Reuse

- `StatStrip`/`StatTile` from `components/composites`; `Badge` (`outline`) and `StatusBadge` from `components/ui`; `columnVisibilityFeature` with `DataTable`; `useIsMobile` from `hooks`; `sourceStatusMessage` from `source-errors.ts`; Tailwind 4 container queries, already used by `Card`.

## Out of scope

Copy and terminology (batch 2), Chat (batch 3), the remaining minor findings (batch 4). The full list is in the critique snapshot kept outside the repository.
