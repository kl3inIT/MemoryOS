# Library tabs and the Sources a member reads from

Status: **accepted 2026-09-28; backend and web implemented** on `phamnhatanh811/library-sources-view`. It follows the
[library hub](../library-hub/design.md). No Linear issue yet. The interactive mockup and design board are under
[mockups/](mockups/).

## Problem

The library hub put a second menu (a rail of eight views) beside the application sidebar. The owner found two stacked
menus hard to read. *Cuộc họp* appeared in both.

The owner also wants members to see the Sources their organisation's documents come from, without leaving `/library`.
Until now a member only saw a Source's name as a filter on *Tài liệu tổ chức*. That filter lists only active and
indexing Sources.

## Owner decisions (2026-09-28)

1. **Tabs, not a second menu.** The library views sit in one row of tabs under the page title (Notion, Glean and the
   ChatGPT library do the same).
2. **Only what already exists.** The Sources view shows the Source model the product already has: `SourceSummary`
   statuses, access, providers and the Sources page's list. It adds no fields, metrics, banners or statuses of its
   own.
3. **Members only.** The library shows the Sources the way a member reads them. Managing a Source (synchronizing,
   pausing, uploading, Groups, visibility, deleting) stays on `/admin/sources`, where administrators and Source
   managers already configure it.

## Design

### Navigation

| Tab | Views | Notes |
| --- | --- | --- |
| *Gần đây* | `recent` | Unchanged |
| *Của tôi* | `ready` (*Tệp*), `pending` (*Đang xử lý*), `trash` (*Thùng rác*) | The views are a choice below the row; the active one shows its count |
| *Được chia sẻ* | `shared` | Meetings shared with the person and files of Agents they use |
| *Có gắn sao* | `starred` | Unchanged |
| *Tổ chức* | `sources` (*Nguồn dữ liệu*), `documents` (*Tài liệu*) | Only with `SEARCH_READ`, as the documents view was |

- The storage bar leaves the rail and ends the tab row, with *Xem tệp lớn nhất*.
- **The meetings view is removed.** A person's own meetings are the Meetings page; meetings shared with them stay in
  *Được chia sẻ*, *Gần đây* and *Có gắn sao*.
- `GET /api/chat/library/meetings` had no other caller, so it is removed with `LibraryShelfService.meetings`.
- The row is the registry `Tabs` (line variant, as the Agents and Source pages use), and the view on screen is the
  open tab's `TabsContent`, so every tab controls a real panel. The views inside a tab are the registry
  `ToggleGroup`, the control the library's toolbar already uses for *Tên / Nội dung*. A second `TabsList` in its
  default variant failed axe's contrast check.

### Sources (`view=sources`)

- **The list.** The Sources page's list, extracted into `features/sources/source-list.tsx` and shared by
  `/admin/sources` and the library. It has the search, the status, provider and access filters, one section per
  provider with its totals, and the columns *Tên · Lập chỉ mục gần nhất · Trạng thái · Truy cập*.
- **The member's version** (`ReadableSources`):
  - The document column counts **the documents the member may read** (*Tài liệu bạn đọc được*). A SYNC Source's
    total would count files the member cannot open.
  - The access cell adds the member's own Groups that grant a PRIVATE Source.
  - The name cell adds *Người phụ trách* when one is recorded.
  - There is no action column.
- **Statuses.** The status filter offers only what a member can see: `NOT_STARTED`, `INDEXING`, `ACTIVE`, `PAUSED` (also
  while pausing) and `FAILED`.
- **A Source's name** opens *Tài liệu* narrowed to it (`view=documents&sourceId=`) while Search serves some of its
  documents to the member. A paused Source keeps its documents out of Search, and a new one holds none, so their names
  open nothing.
- **Filters in the address.** The search and the filters live in the library's address (`q`, `sourceStatus`,
  `provider`, `access`), as every other library view's do. The administration page keeps its own state.
- **Composition.** The library imports no screen of the connector capability (ADR 0015 step 4 removed `library →
  sources`). `LibraryPage` takes a `sources` slot (`LibrarySources`). Chat's `ChatLibraryPage` passes it through, and
  the `/library` route hands in `ReadableSources`. Without the slot, *Tổ chức* holds its documents alone.
- The list rereads every 30 seconds (`IDLE_SOURCE_POLL_MS`), because nothing reports running work to a member.

### Contract

- **`GET /api/chat/library/sources`** (`listChatLibrarySources`) requires `SEARCH_READ` and is bounded to 500. It
  returns `{id, name, type, access, status, readableDocuments, lastSucceededAt, groups, managerName}`:
  - every Source whose read scope admits the caller, whatever its state except DELETING (a SYNC Source only while one
    of its documents admits the caller);
  - `readableDocuments` is counted under `DOCUMENT_READ_SCOPE` without its Source-status condition;
  - `groups` names only the caller's own Groups granted a PRIVATE Source.

  No error details are returned.
- `JdbcSourceQueryRepository.DERIVED_STATUS` is the one status expression the Source summary and this catalog share.

## Excluded

- Any Source management in the library. The list offers no manage link, because `/admin/sources` is the place to
  configure.
- A Source detail page inside the library, and the withdrawn proposal fields: `failedItemCount`, attention filters, the
  items status filter and `SourceRunError.itemId`.
- Showing a paused Source's documents: Search keeps them out until the Source resumes.
