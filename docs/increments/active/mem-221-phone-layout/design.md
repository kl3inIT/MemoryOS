# MEM-221 Phone layout of four list pages

First fix batch of the 2026-10-03 UI critique (26/40, [issue list](../../../../.impeccable/critique/issues/lan-2/README.md)): its one P1 finding. Frontend only; the P2 groups and the minor findings wait for an owner decision on scope.

## Requirement and design

At 390×844 four list pages hide the thing a row is told apart by.

1. **L2-1a Agents.** A card's edit, share, "…" and pin actions float over its top-right corner (`agent-card.tsx`). A pointer reveals them on hover; a touch screen cannot hover, so they are always shown there and cover the name and the first description line. Where the screen cannot hover (`no-hover`), the actions leave the corner and take their own row under the description, right-aligned above *Bắt đầu chat*; the name stops reserving the corner. Hover devices keep the floating bar at every width.
2. **L2-1b Library.** A file row keeps download, star and "…" visible below `md`, and its name is one truncated line, so "bao-cao-tai-chinh-quy-3-2026.xlsx" reads "bao-cao-tai-…" (`library-rows.tsx`). Below `sm` the name takes up to two lines and breaks inside a word, and the inline download button is hidden: the "…" menu already downloads. Star stays, as the menu does not offer it.
3. **L2-1c Models.** A provider's name, address and four actions share one row, which leaves the address about 80 px and breaks it mid-word (`provider-connection-card.tsx`). Below `sm` the name and address take the whole row and the actions wrap under them, right-aligned.
4. **L2-1d Meetings.** The search shares a wrapping row with two selects but may shrink to nothing, so it collapses to "Tìm" instead of the selects wrapping (`meetings-page.tsx`). Below `sm` the search takes a row of its own and the two selects share the next one.

## Reuse

The `no-hover` variant and `hoverReveal`; the Library row's existing "…" menu; Tailwind's `sm` breakpoint, `line-clamp` and `wrap-anywhere`. No new component.

## Out of scope

The P2 groups (controls before content, the administration menu, one pattern per list page, copy of destructive actions) and the minor findings L2-n1 to L2-n10, among them the Meetings filters kept in component state (L2-n8) and meeting titles truncated in the list. Keyboard shortcuts and bulk selection are MEM-219 and MEM-220.
