# Plan

Branch `dathip04/frontend-audit`; wave 1 runs in parallel worktrees on disjoint files and merges into the branch, wave 2 sweeps the whole tree.

## Wave 1

- [ ] **T · Tooling and tokens.** Shadcn skill (`.skills/shadcn`), `@shadcn/lint` and Oxlint `jsx-a11y`/`import`; dead token classes fixed; unused/duplicate `--color-*` removed; z-index/elevation scales; dot-matrix CSS out of inline `<style>`; nginx `Permissions-Policy`; `theme-color`; unused dependencies (`cn`, `@tanstack/react-table`, `@assistant-ui/react-markdown`) removed; knip; bundle budget.
- [ ] **S · Shell and routing.** `AppShell` layout route; administration table; Chat runtime in the Chat layout (and its per-render effect); identity `beforeLoad`, route loaders; zod `validateSearch` everywhere and typed `useSearch`; URL filters for Search, Audit log and Library; Sentry root error handlers.
- [ ] **M · Meeting and preview.** Recorder subscription split and virtualized transcript; one preview component for Library, Search and Chat; lazy pdf.js, docx and charts.
- [ ] **R · Sources and Groups.** Shared create/receipt/toast/paging hooks; conditional polling; seed-into-state effects replaced by drafts over server data; the largest Sources components split.

## Wave 2

- [ ] Generated query/mutation factories everywhere; MSW in unit tests.
- [ ] Data tables on TanStack Table (the shadcn Data Table pattern, server-side paging and sorting kept through `manualPagination`/`manualSorting`): users, library, Source items and runs, Group members, models and provider models, document sets, audit log, Chat history. Static tables stay plain `Table`.
- [ ] shadcn `Field` forms; mutation failures surfaced and rolled back; `useDebouncedValue`; `formatUiDate` everywhere.
- [ ] React 19 APIs; hooks gathered in `src/hooks`; `noUncheckedIndexedAccess`.
- [ ] `@shadcn/lint` and `jsx-a11y` findings to zero, rules at error; banners on `Alert`; axe in Playwright specs; oxfmt Tailwind sorting last.

## Verification

Recorded per task as it lands.
