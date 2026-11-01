# Library toolbar: icon buttons, one search field, no stray focus ring

## Problem

On `/library` the filter and display buttons carried a word on desktop, the Sources view's collapse control was a
text button, and three different search boxes (the toolbar's `InputGroup`, the composer picker's own `InputGroup` and
the Sources list's `Input type="search"`) differed in height and in how they cleared. Three defects showed in the
same row:

- After a menu or popover closed, the trigger drew a black focus ring even when the pointer had opened it. Radix hands
  focus back to the trigger by script on close, and Chromium then matches `:focus-visible`. Measured on the e2e
  fixtures: after choosing a display option with the mouse, the order menu was `:focus-visible`.
- A tab that already held a keyboard ring kept it when the pointer clicked it, because a click on the focused element
  does not reset `:focus-visible`.
- The tab row showed a scrollbar on desktop. The active tab's underline hangs 2 px below the list, and
  `overflow-x-auto` turns that overhang into a scrollable extent.

## Decision

- **Icons only.** `Bộ lọc` (with the count of active filters beside the icon), `Sắp xếp` and the two layout buttons
  `Dạng danh sách` / `Dạng lưới` (`aria-pressed`, no menu) are `IconButton`s at the field's height; the Sources list's `Thu gọn / Mở rộng tất cả` is an icon that follows its state
  (`ChevronsDownUp` / `ChevronsUpDown`). Accessible names are unchanged.
- **Tooltips from one wrapper.** `TooltipIconButton` (already in `components/composites`) shows the button's function
  on hover and focus. It gains an optional `tooltip` so a row action whose label names the file (`Tải về a.pdf`) shows
  only the function (`Tải về`). Every icon-only button of the library surfaces uses it; native `title`s that said the
  same thing are gone.
- **One search field.** `SearchField` in `components/composites` (icon, text, a clear button with a tooltip, an end
  slot) serves the library toolbar (`LibrarySearchField` adds the names/contents switch), the composer picker and the
  Sources list. `sources` may not import `library`, so the shared piece lives in `components/composites`
  ([feature folders](../../../conventions.md#frontend-feature-folders)). The Sources list's field is now a plain text
  input, so the browser's own clear mark no longer doubles the button.
- **Pointer-opened overlays leave focus alone.** `usePointerOpenedTrigger` (in `hooks`) replaces two copies of the
  same `byPointer` ref in the row menus and serves the toolbar's two triggers: a pointer-opened menu or popover does
  not return focus on close, a keyboard-opened one does, so the ring stays for the keyboard.
- **Tabs.** A pointer press on the tab row suppresses the ring until the next key press; arrow keys bring it back. The
  list scrolls sideways on a phone only, inside a wrapper that keeps room for the underline.
- **No page description.** The Library's `PageHeader` has no description line.

## Not done

`ChatFilePicker` (the composer's attach control and the agent and Project file pickers) keeps its icon buttons without
tooltips: it is Chat's attachment surface that lives in the library folder, not a Library screen.
- **Rows or cards is remembered.** `useLibraryLayout` keeps the choice per person in `localStorage`
  (`memoryos:library-layout:<actor>`), so it survives a change of tab or a visit to another page. A browser
  preference only; unavailable storage leaves the choice for the visit.
- **Reference-view filters share one icon.** The kind, file-type and Source choices of Được chia sẻ, Có gắn sao and
  Tài liệu sit in one `Bộ lọc` popover with a count, as the owned files' filter does.
