# Plan — a search on the Projects page

- [x] `foldText` shared in `web/src/lib/fold-text.ts`; prompt shortcuts use it
- [x] `projectsSearchSchema` (`q`) on the `/projects` route; `useProjectsFilter` keeps the box and the address in step
- [x] `SearchField` above the list; narrowing by name and description without marks
- [x] A narrowed list renders without `SortableList` or handles, with the reorder hint
- [x] No-match `EmptyState` with *Xóa bộ lọc*
- [x] English catalog entries for the new strings
- [x] `chat-projects-page.test.tsx`: narrowing, address, no handles while narrowed, empty state
- [x] `tests/e2e/chat-projects.spec.ts`: narrowing, address across a reload, no drag while narrowed, empty state
- [x] `docs/tests/chat.md` lists the checks
- [ ] `pnpm --dir web check` and the full Playwright suite on the pull request's CI

## Closed (2026-10-19)

PR #686 merged 2026-10-15 with `pnpm --dir web check` and the browser suite green on its CI.
