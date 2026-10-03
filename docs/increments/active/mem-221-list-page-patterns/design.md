# MEM-221 One pattern per list page

Second fix batch of the 2026-10-03 UI critique (26/40, [issue list](../../../../.impeccable/critique/issues/lan-2/README.md)): its P2 groups and minor findings. Frontend only. The [first batch](../mem-221-phone-layout/design.md) fixed the P1 finding.

The critique left the direction of each P2 group to the owner. This batch takes one direction per group, stated below, so the owner can accept or change each one in review.

## Requirement and design

### Controls before content

- **L2-2a Library.** The tab row, a storage summary, three view choices, a search, a search-mode switch, a filter, a sort select and a layout switch stood above the first file. The toolbar is now a search, the filter and one *Cách hiển thị* menu that holds the order and the layout (`LibraryDisplayMenu`, on every view). The search field itself says whether it reads names or contents, since that changes what a query finds. The tab row shows the storage only when the account has used 90% of its limit, with *Xem tệp lớn nhất*; the figures already live in the Library's settings dialog.
- **L2-n4.** A view with no file, no search and no filter leaves the toolbar out: there is nothing to search, filter or lay out.
- **L2-2b Meeting detail.** Not in this batch. The meeting detail page is being rebuilt by the transcript work in flight, which removes the stat strip the finding is about.

### The administration menu

- **L2-3a.** The menu listed every section open, 20 links on one screen, and scrolled on a laptop. A section folds (`Collapsible` on the sidebar group). Every section is open while the menu fits the screen; when it would scroll, every section but the open page's folds. A section the person folds or opens stays that way on this browser. The icon-only sidebar keeps every section open since it has no section labels.
- **L2-3b.** *Thêm nguồn* leaves the menu. It was an action listed as a place; the Sources page carries the same link as its primary button.

### One pattern per list page

- **L2-4a.** A single-choice filter is the registry `Select` on every page: Meetings and Users join the Library. A multi-choice filter stays a menu (Search, the Library's filter popover).
- **L2-n8.** The Meetings filters live in the address (`q`, `status`, `period`), so a reload or Back returns the same list. The list opens with every meeting; a period is a choice.
- **L2-4b.** A page's primary button is the default size with a leading icon: Sources gains the icon, Users the size.
- **L2-4c, L2-n1.** Two date shapes, both through `formatUiDate`: `formatUiDay` (a day) and `formatUiMoment` (a day and its time). 23 call sites stop calling `toLocaleDateString`, `toLocaleString` and `Intl` themselves. Numbers on the Sources tiles follow the UI locale.
- **L2-4d.** The Sources page icon is a plug, as its menu entry is; the book stays with documents.
- **L2-n7.** The Users table shows *Account type* only when the rows on the page hold more than one type. Every membership is `STANDARD` today, so the column is left out ([invitation spec](../../../specs/invitation.md)).

### Copy

- **L2-5d.** A conversation is *hội thoại* everywhere; `check:i18n` rejects *cuộc trò chuyện* and *cuộc chat*.
- **L2-5a, L2-5c.** Deactivating a user and deleting a conversation say what is lost and whether it comes back.
- **L2-5b.** Not done: the count the finding asks for needs a total the paged sessions list does not return.

### Minor findings

- **L2-n2.** 1 GiB reads "1 GB", not "1.024 MB".
- **L2-n3.** A provider preset that already has a saved connection offers *Thêm kết nối* instead of *Kết nối*.
- **L2-n5.** When the organization has a search connection and Web search is still off, the composer menu says the selected model cannot use it.
- **L2-n6.** The Models page waits behind the brand loader and its sections behind skeleton rows, as the other pages do.
- **L2-n9.** On a coarse pointer the registry select trigger and the toggle group item grow to 44 px as buttons already do; the Library's tabs and filter chips follow; a checkbox takes a 44 px hit area around its 16 px box.
- **L2-n10.** The chat page has one `h1`.

### After the third critique

The rescan after this batch scored 25/40 against 26: two of its three major findings were this batch's own. Folding the administration menu and moving the search mode into the display menu each traded noise for recall. Both are corrected above. The rescan's other findings in this batch: the Meetings period default, the page-size control on Users and Groups (`PageSizeSelect`), and the Library on a phone (one scrolling tab row, icon-only filter and display buttons, a 44 px filter pill).

### After the fourth critique

The rescan scored 27/40. What it changed: a select is as tall as the field beside it (`--control-height-md`); a Library row always shows its menu and reveals only the star and the download on hover (`rowShortcutReveal`); a stat tile that filters carries a filter mark from `sm` up; the chat search entry shows its shortcut; a row under a day heading does not repeat the date. Left out by the owner's choice: a Help entry in the account menu and the product name in copy.

### A phone names the page once

Owner feedback on the 390 px captures: the page looked unfinished, and the title sat twice, in the shell bar and in the page header below it. Below `md` the shell bar is the page's name. A `PageHeader` whose title equals the bar's (`ShellBarTitle`) keeps its title and description for a screen reader and leaves the screen to its actions and the content; a header with a different title (a detail or a create page) stays. In the same batch: the Library's upload is a primary action as on the other list pages, its tabs drop their icons and fade at the end of the row, a day's files are one divided card as a day of meetings is, the Users search takes a row and its two filters share the next, and a field is as tall as the buttons beside it on a touch screen.

## Reuse

Registry `Select`, `DropdownMenu` radio groups, `Collapsible`, `Skeleton`, `BrandLoader`; `formatUiDate`; the coarse-pointer rule in `base.css`; the Users page's URL-search pattern (`validateSearch` with a zod schema). No new composite.

## Out of scope

L2-2b and L2-5b, for the reasons above. From the third critique: the Users row menu as a `DropdownMenu`, the Meetings pages' own date formatting, the Library's one-card-per-day list and its description on a phone. Keyboard shortcuts and bulk selection are MEM-219 and MEM-220.
