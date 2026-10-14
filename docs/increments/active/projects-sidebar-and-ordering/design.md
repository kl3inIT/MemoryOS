# Projects in the sidebar, the Projects page and a chosen order

## Requirement

Owner request 2026-10-13, on Projects in Chat:

1. The sidebar's *Dự án* section opens and closes.
2. A project in the sidebar opens to show its conversations.
3. The Projects page gets a real page layout and richer rows.
4. Loading a project list uses the application's loader instead of a line of text.
5. A conversation moves into a project from a submenu in one click, and leaves a project from its own
   menu item (reference: the ChatGPT conversation menu).
6. The person chooses the order of their projects by dragging.

Not in scope: pinning a conversation, a conversation-files entry in the menu, plugins.

## Design

### Sidebar section

- The full-width *Dự án* heading button, with its chevron, opens and closes the section; the state is
  kept in `localStorage`. A separate plus button creates a project.
- The section lists the first five projects in the person's order. If at least one project exists,
  *Xem tất cả dự án* links to `/projects`. The project being viewed (its page, or the project of the
  open conversation) is listed even when it is outside the first five.
- Each project row has a chevron disclosure button that opens the project in place. An open project lists its five most
  recent conversations from `GET /api/chat/projects/{id}/sessions?limit=5`, rendered with
  `ChatSessionRow` so rename, move, archive and delete work as in *Hội thoại gần đây*. A full page of
  five shows *Xem thêm*, which loads the next five in place; the project page opens only from the row's
  *Xem dự án* menu item. The project of the open conversation opens by
  itself. Which projects are open is component state and is not persisted.
- A row's *…* menu opens the project page, edits it through `ProjectEditor`, or deletes it through
  `ProjectDeleteDialog`; deleting preserves its conversations. The row remains a drop target for a
  dragged conversation.
- Loading shows `Skeleton` rows: `BrandLoader` is a page-scale loader and does not fit the column.
- A conversation in a project still appears in *Hội thoại gần đây*. That list is not filtered in this
  increment.

### Projects page

- `SettingsLayout` and `PageHeader` (icon, title, *Tạo dự án* action; no description line) replace
  the hand-rolled heading row and the hardcoded width.
- One column of rows: icon, name, one-line description, *N tệp · Cập nhật …*, and a `⋯` menu with
  *Sửa* (the existing `ProjectEditor`) and *Xóa* (`ConfirmDialog`; conversations are kept). A single
  column keeps the same `SortableList` usable here and in the sidebar.
- The whole row is the hover card, including its `⋯` menu, and a row is dragged from anywhere. The
  keyboard handle stays in the row as a visually hidden button. Letting go of a dragged row never opens
  it: dnd-kit stops the click from reaching the page but not the browser, so the list cancels that
  click's default itself.
- Loading uses `BrandLoader`; a failed load is an `EmptyState` with a retry; the empty state stays.
- The project page's own loading state in `chat-page.tsx` uses `BrandLoader` too.

### Conversation menu

- *Chuyển đến dự án* is a submenu: *Dự án mới* first, then the person's projects in their order. One
  click moves the conversation; the conversation's current project is marked and disabled. *Dự án mới*
  opens `ProjectEditor` and moves the conversation into the project it creates.
- *Gỡ khỏi dự án* appears only for a conversation that is in a project.
- The move dialog with its radio list is removed. A failed move stays visible to the person.

### Chosen order

- `chat_project` gains `position INTEGER NOT NULL`. The migration backfills each owner's projects in
  the order the list shows today (`updated_at DESC, id`), so nothing moves on deploy.
- `GET /api/chat/projects` orders by `position ASC, updated_at DESC, id`. `position` is a sort key and
  is not published in `ProjectView`: the list order is the contract.
- A new project takes the first place (one less than the owner's lowest position), without rewriting
  the other rows. Editing a project no longer moves it to the top.
- `PUT /api/chat/projects/order` (`reorderChatProjects`) takes `projectIds`, every project of the actor
  exactly once, and writes positions `0..n-1` in that order under the owner lock that create and
  delete already take. A list that is not exactly the actor's projects is a conflict, so a client
  holding a stale list reloads instead of losing a project's place. Reordering changes neither a
  project's `revision` nor its `updatedAt`.
- The web reorders with `SortableList` in the sidebar and on the Projects page, shows the new order at
  once and restores the server's order when the write fails, as pinned agents do. The list keeps the
  dropped order itself until the save ends, so the row never returns to its old place for a frame, and
  rows are memoized so dragging one does not render the others.

### Project page

- Under the composer, the page shows two tabs: *Trò chuyện* (the project's conversations) and *Tệp*
  (its files, with *Thêm tệp*). The files are no longer a collapsible block above the composer.

## Reuse

- `Collapsible`, `Skeleton`, `DropdownMenuSub`, `ConfirmDialog` from `components/ui`.
- `SettingsLayout`, `PageHeader`, `EmptyState`, `SortableList`, `hoverReveal` from `components/composites`;
  `BrandLoader`.
- `ChatSessionRow`, `ProjectEditor`, `ProjectIcon`, `projectsOptions`.
- The pinned-agents order (`actor_pinned_persona.position`, `replaceChatPersonaPins`) as the precedent
  for a person's own order and its optimistic update.
