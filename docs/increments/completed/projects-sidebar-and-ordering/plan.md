# Plan — projects in the sidebar, the Projects page and a chosen order

## Sidebar

- [x] *Dự án* section opens and closes; state kept in `localStorage`
- [x] Full-width section toggle and project rows retain visible chevrons; each project row has an action menu to view, edit or delete
- [x] First five projects, an always-available *Xem tất cả dự án* link when projects exist, and the viewed project always listed
- [x] A project opens to its five most recent conversations with `ChatSessionRow`; *Xem thêm*
- [x] The project of the open conversation opens by itself
- [x] `Skeleton` rows while projects or conversations load

## Projects page

- [x] `SettingsLayout` + `PageHeader`
- [x] Rows with description, file count, update time and a `⋯` menu (*Sửa*, *Xóa*)
- [x] `BrandLoader` on the page and on the project page's loading state; `EmptyState` on failure

## Conversation menu

- [x] *Chuyển đến dự án* submenu with *Dự án mới* and one-click move
- [x] *Gỡ khỏi dự án* for a conversation in a project
- [x] Move dialog removed; `tests/e2e/chat-workspace.spec.ts` follows the new menu

## Chosen order

- [x] Migration V159: `chat_project.position`, backfilled in today's order
- [x] List ordered by `position`; a new project first
- [x] `PUT /api/chat/projects/order`; `openapi.yml` and `web/src/lib/hey-api` regenerated
- [x] `SortableList` in the sidebar and on the Projects page with an optimistic order

## Consolidation and gates

- [x] English catalog entries for every new string; stale keys removed
- [x] `docs/specs/chat.md` and `docs/tests/chat.md` describe the delivered contract
- [x] Web gate, each part run locally: `tsc -b`, the unit suite (one failure, the load-sensitive `chat-web.test.tsx` that fails the same without this change), oxlint, oxfmt, the i18n audit, knip, the generated client and route tree stable, bundle budget
- [x] `ChatPersistenceIntegrationTest`, `ChatSessionApiIntegrationTest` and `OpenApiContractTest` on PostgreSQL with V159 applied; `ModulithArchitectureTest`, `CoreDependencyRulesTest`
- [x] `clean check` and the complete Playwright suite on the pull request's CI (one complete local run: 207 passed, 5 failed — two from this change and fixed, three from fixture state or load)
  PR #628: the backend checks and the six Playwright shards passed on CI; #643 likewise.
- [x] Browser verification of the sidebar, the Projects page and the conversation menu on staging
  2026-10-28 on `87eedb29` at 1440×900: the sidebar's *Dự án* lists the two projects and closes on a click; `/projects` shows each with its icon, file count and update date; a conversation's menu offers *Chuyển đến dự án* with *Dự án mới* and both projects.

## Staging acceptance (2026-10-19)

Moving a conversation into and out of a Project works, but creating a Project failed (400 "Invalid icon."), fixed by PR #794; the sidebar shows no chevrons and hides "Xem tất cả dự án" below six Projects, unlike the checked plan items.
