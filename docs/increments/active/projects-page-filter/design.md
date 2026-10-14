# A search on the Projects page

## Requirement

With more than about fifteen Projects the Projects page can only be scrolled. The person needs to narrow the
list by typing. Frontend only: the page already holds every Project (`projectsOptions()`), so no API changes.

## Design

- `SearchField` (the shared list search box) sits above the list whenever the person has at least one Project,
  labelled *Tìm dự án*.
- The list narrows as the person types, by name and description, ignoring case and Vietnamese marks
  (`foldText` in `web/src/lib/fold-text.ts`, the fold the prompt shortcuts already used, moved there to be shared).
- The words live in the address as `q` (`validateSearch` on `/_authenticated/projects/`). As in the Library, the box
  holds what is typed and the address follows after 250 ms, replacing its entry; Back or a link sets the box.
- A narrowed list cannot be reordered: its rows are rendered without `SortableList` and without the keyboard
  handles, and a muted line says *Xóa từ khóa để sắp xếp lại dự án.* Dragging inside a subset would place rows in
  a full order the person cannot see.
- When nothing matches, an `EmptyState` says *Không có dự án nào khớp* / *Hãy thử từ khóa khác.* with *Xóa bộ lọc*.
- The shared `SearchField` is unchanged: Escape keeps its current meaning in every list search and dialog.

## Reuse

`SearchField`, `EmptyState`, `useDebouncedValue`, the Library's address-sync pattern (`use-library-view.ts`),
`foldText` (moved from `prompt-shortcuts-api.ts`).
