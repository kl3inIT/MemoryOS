# Web application guide

For work under `web/`: the Vite, React 19, TanStack Router and Query, Tailwind and shadcn/ui application. This page points to the canonical rules in [docs/conventions.md](../docs/conventions.md) and the guidelines; it does not restate them. When they disagree, the linked source wins.

## Commands

Run from the repository root with `pnpm --dir web <script>`, or inside `web/`.

- `pnpm check` is the gate CI runs: generated API client and route tree stability (`check:api`, `check:routes`), Playwright image pin (`check:ci`), i18n audit (`check:i18n`), `lint` (Oxlint, warnings denied), `format:check` (oxfmt), `typecheck` (`tsc -b`), `test:unit` (Vitest).
- `pnpm build` also enforces the font assets and the initial-load gzip budget.
- `pnpm typecheck` and `pnpm build` are the type check; `tsc -p` is not.
- `pnpm test:e2e` runs the Playwright suite against synthetic fixtures ([testing guideline](../docs/guidelines/testing.md)).
- `pnpm generate:api` regenerates `src/lib/hey-api` from `../openapi.yml`; never edit generated files or `src/routeTree.gen.ts` by hand.
- `pnpm exec knip` reports unused files, exports and dependencies (configured in `knip.json`).

## Where things live

| Path                        | Holds                                                                                                                                                                                         |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/components/ui`         | shadcn/ui registry components (`components.json`, `radix-nova`) and the MemoryOS wrappers `Button`, `IconButton`, `TextButton`, `StatusBadge`, `ConfirmDialog`, `PageHeader`/`SettingsLayout` |
| `src/components/composites` | Product patterns shared by several features, built only from registry primitives and tokens                                                                                                   |
| `src/components/app-shell`  | `AppShell`, `AppShellHeader`, the administration table `admin-pages.ts`                                                                                                                       |
| `src/features/<capability>` | Screens of one backend capability; `preview` and `theme` are shared kits                                                                                                                      |
| `src/routes`                | TanStack Router file routes; `_authenticated` is the signed-in layout                                                                                                                         |
| `src/lib`                   | The Hey API client setup (`api.ts`), generated client (`hey-api/`), query client, Sentry, helpers                                                                                             |
| `src/hooks`                 | Hooks shared by several features                                                                                                                                                              |
| `src/i18n`                  | Catalogs and the translation helpers                                                                                                                                                          |
| `src/styles`                | `tokens.css` (semantic tokens, light and dark) and `theme.css`                                                                                                                                |

## Rules to check before you edit

- **Reuse first**: `components/ui`, then `components/composites`, then the feature; install a missing registry control with `pnpm exec shadcn add` instead of writing it ([component reuse](../docs/conventions.md#component-and-library-reuse), [shadcn/ui registry](../docs/conventions.md#shadcnui-registry)). The shadcn agent skill is in `.skills/shadcn`.
- **Page structure**: `PageHeader`, detail pages, composites and stat strips follow [page headers](../docs/conventions.md#page-headers), [detail pages](../docs/conventions.md#detail-pages) and [composites](../docs/conventions.md#composites).
- **Interaction**: `tone`, `prominence` and `size` vocabulary, `ConfirmDialog` for destructive actions, busy state from work the person started ([frontend interaction contracts](../docs/conventions.md#frontend-interaction-contracts)).
- **Colour**: semantic tokens only ([colour and design tokens](../docs/conventions.md#colour-and-design-tokens), [design token guideline](../docs/guidelines/design-tokens.md)).
- **Feature folders and imports**: a feature imports another only in the backend dependency direction ([frontend feature folders](../docs/conventions.md#frontend-feature-folders)).
- **Shell, routes, polling, previews, errors, CSP and bundle budget**: [frontend shell, routes and runtime](../docs/conventions.md#frontend-shell-routes-and-runtime).
- **API client**: SDK calls throw `ApiError` and carry the CSRF header by default; read problem members through `problemOf` ([published API contracts](../docs/conventions.md#published-api-contracts)).
- **Data layer, forms and tables**: follow the "Frontend patterns" section of [docs/conventions.md](../docs/conventions.md).
- **Copy and language**: every visible string goes through the catalogs; show safe error copy from problem codes, never backend text ([localization](../docs/specs/localization.md)).
- **Tests**: the narrowest boundary that catches the regression ([testing policy](../docs/conventions.md#testing)); a test sits next to its subject.

## Common mistakes

- Hand-rolling a checkbox, table, tab strip, tooltip or disclosure instead of the registry component.
- Raw palette classes, hex colours or opacity as a disabled state.
- Rendering `AppShell` in a page, or adding an administration page without a row in `admin-pages.ts`.
- Keeping filter state in component state when it belongs in the URL, or reading search params without `validateSearch`.
- Polling on a fixed interval when nothing runs, or disabling a control on a background refetch.
- An inline `<style>` element, which the CSP drops.
- An eager import of a viewer, chart or grammar, which grows the initial load.
- Passing `throwOnError` or the CSRF header at a call site, or reading `result.error`.
- Importing `chat` from `library`, `meetings`, `voice`, `search`, `mcp` or `identity`.
- Exporting a helper only so a test can call it.
