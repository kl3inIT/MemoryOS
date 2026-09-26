# Plan

Branch `dathip04/frontend-audit`; wave 1 runs in parallel worktrees on disjoint files and merges into the branch, wave 2 sweeps the whole tree.

## Wave 1

- [x] **T · Tooling and tokens.** Shadcn agent skill (`.skills/shadcn`, linked from `.claude/skills` and `.agents/skills`) and `@shadcn/lint` 0.2.0 installed; dead token classes fixed; unused `--color-*` mappings and orphan raw tokens removed (`sidebar-*`, `chart-neutral` and the full `status-*` scale kept for shadcn primitives), duplicate `action-selection` gone; dot-matrix keyframes in a stylesheet; nginx `Permissions-Policy` (`microphone` and `display-capture` for self); `theme-color` follows `--surface-base`; `cn`, `@tanstack/react-table` (returns with the data tables) and `@assistant-ui/react-markdown` removed, caret ranges pinned; knip configured; initial-load gzip budget in `pnpm build`; English catalog loaded on demand.
  - Measured 2026-09-26 before wiring rules (they join `pnpm lint` in wave 2 once clean, because `--deny-warnings` admits no baseline): `@shadcn/lint` 2,500 warnings (no-restyle 1,914, no-arbitrary-values 422, no-unknown-classes 64, no-inline-styles 53, no-raw-colors 29, require-static-classes 18); `jsx-a11y` 244 (prefer-tag-over-role 185); knip 77 unused exports and 38 unused exported types, no unused files or dependencies; `noUncheckedIndexedAccess` 110 errors.
  - Initial load 395.4 KiB gzip before, 364.6 KiB after the English catalog split; budget 380,000 bytes. Every app key resolves to the same text in both languages as before (script comparison over 3,584 keys).
  - `pnpm typecheck`, `lint`, `format:check`, `check:i18n`, `vite build`; vitest `src/i18n`, `src/features/theme`, `src/components/ui`, and the four suites that switch to English.
- [x] **S · Shell and routing.** `AppShell` layout route; administration table; Chat runtime in the Chat layout (and its per-render effect); identity `beforeLoad`, route loaders; zod `validateSearch` everywhere and typed `useSearch`; URL filters for Search, Audit log and Library; Sentry root error handlers.
- [x] **M · Meeting and preview.** Recorder subscription split and virtualized transcript; one preview component for Library, Search and Chat; lazy pdf.js, docx and charts.
- [x] **R · Sources and Groups.** Shared create/receipt/toast/paging hooks; conditional polling; seed-into-state effects replaced by drafts over server data; the largest Sources components split.

  - S: `AppShell` once in `_authenticated`, pages draw into it through `AppShellHeader`; `admin-pages.ts` drives sidebar, titles, access and entry; the Chat runtime stays above the shell because the sidebar lists conversations on every page, now keyed by route matches, its per-render effect fixed; identity ensured in `beforeLoad`, the session boundary still owns 401/403 and every failure screen; admin loaders warm data only for a person the page admits (`mayWarmAdminPage`); zod `validateSearch`; Search, Audit log and Library filters in the URL (Library writes the settled, debounced search); Sentry `onCaughtError`/`onUncaughtError`.
  - M: `useRecorderValue` selectors, meeting page split into modules, transcript virtualized with follow-while-recording, a hidden live region and `aria-setsize`/`aria-posinset`; `FilePreview`; pdf.js, charts and the Chat file modal lazy.
  - R: shared creation, upload, operation-notice and cursor-paging hooks; no production Sources component above ~600 lines; Group drafts at the page; `useDebouncedValue`; fast polling only while work runs, 30 s while idle.
  - Merged: `pnpm typecheck`, `lint`, `format:check`, `check:i18n`, vitest 150 files / 728 tests, `vite build` with the budget; the image build measured 376.3 KiB initial gzip (Sentry release injection adds about 7 KiB over a local build), budget 390,000 bytes.

## Wave 2

- [x] **Pilot · patterns.** Libraries (TanStack Form 1.33.5 and Table 9.2.4, MSW 2.15.0, Base UI, react-day-picker, shadcn `cn`) and the shadcn `field`, `input-group`, `spinner`, `combobox`, `avatar`, `button-group`, `native-select`, `calendar` adapted to the repo `Button`/`Input`/`Select` API and tokens; MSW node server with the generated `handle<Operation>` factories (Hey API `msw` plugin); `.oxlintrc.target.json` and `pnpm lint:target`; Document Sets converted end to end as the reference (generated factories and keys, `DataTable`, `useAppForm`, MSW tests). Patterns recorded in [conventions](../../../conventions.md#frontend-patterns).
  - Target lint 2026-09-26 over `src` after the exceptions: 1,077 findings (no-restyle 719, no-arbitrary-values 266, require-static-classes 20, jsx-a11y 61, no-inline-styles 9, no-raw-colors 1, no-unknown-classes 1); Document Sets keeps one (`StatusBadge` needs a pill variant instead of `statusPill()` classes).
  - The Document Set list follows the API's name order with server paging; it no longer lists editable sets first across all pages.
- [ ] Generated query/mutation factories everywhere; MSW in unit tests.
- [ ] Data tables on TanStack Table (the shadcn Data Table pattern, server-side paging and sorting kept through `manualPagination`/`manualSorting`): users, library, Source items and runs, Group members, models and provider models, document sets, audit log, Chat history. Static tables stay plain `Table`.
- [ ] Hey API zod plugin; generated schemas replace hand-written API response schemas.
- [ ] Forms on TanStack Form + zod + shadcn `Field` (all form files); shadcn `input-group`, `spinner`, `combobox`, `pagination`, `avatar`, `button-group`, `native-select`, `calendar` replace their hand-written equivalents; mutation failures surfaced and rolled back; `useDebouncedValue`; `formatUiDate` everywhere.
- [ ] React 19 APIs; hooks gathered in `src/hooks`; `noUncheckedIndexedAccess`.
- [ ] Application sidebar on the shadcn `sidebar` with the Onyx look kept; before/after screenshots of app, admin, settings, collapsed and mobile.
- [ ] `@shadcn/lint` and `jsx-a11y` findings to zero, rules at error; banners on `Alert`; axe in Playwright specs; oxfmt Tailwind sorting last.

## Verification

Recorded per task as it lands.
