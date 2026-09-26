# Phase 5 — frontend structure, correctness and tooling

## Requirement

Owner decision 2026-09-26: fix every item of the frontend audit (the phase 5 list of [owner-decisions.md](../audit-quality-fixes/owner-decisions.md) group 3 plus the 2026-09-26 best-practice, design-token and stack audit), install the shadcn agent skill and `@shadcn/lint`, best practice even where behaviour or contracts change, one pull request.

## Decisions

- **Design-system lint.** `@shadcn/lint` runs as an Oxlint JS plugin in `pnpm lint`. `no-raw-colors`, `no-unknown-classes`, `no-inline-styles` and `require-static-classes` are errors with named exceptions (brand marks, the `aui-*` marker classes, the `brand-loader` stylesheet, dynamic geometry); `no-arbitrary-values` and `no-restyle` are errors after the sweep, with per-component contracts that allow layout classes on table cells, inputs and triggers. Oxlint also runs `jsx-a11y` and `import`, which the explicit plugin list had switched off.
- **Tokens.** Classes that name a token the theme no longer declares (`bg-surface-accent`, `text-content-danger`, `text-content-on-accent`, `font-main-ui-body-strong`…) generated no CSS; they move to declared tokens. Unused and duplicated `--color-*` mappings are removed, z-index, elevation, radius and type sizes use theme scales instead of arbitrary values, and `theme-color` follows the surface token.
- **Shell and routing.** `AppShell` renders once in the authenticated layout route. Administration pages are declared in one table that drives the sidebar and the administration entry. The Chat runtime lives in the Chat layout only. Identity is ensured in `beforeLoad` and routes preload their queries in `loader`. Search parameters are zod schemas; filters of Search, Audit log and Library live in the URL.
- **Errors.** React root `onCaughtError`/`onUncaughtError` report to Sentry, so errors caught by route error components are no longer lost. Every mutation surfaces its failure; optimistic state rolls back.
- **Performance.** The meeting page subscribes to the recorder only where a value is shown and virtualizes the transcript. pdf.js, docx and chart code load lazily everywhere. Searches debounce the request, not only the render. Polling stops when nothing is running.
- **One preview.** Library, Search and Chat render files through one `preview/` component; Chat stops importing Search.
- **Data layer.** Queries and mutations use the generated `*Options`/`*QueryKey`/`*Mutation` factories; unit tests mock HTTP with MSW instead of mocking the generated SDK module.
- **Forms.** Forms use the shadcn `Field` primitives (`FieldGroup`, `Field`, `FieldLabel`, `FieldDescription`, `FieldError`) with label, description and error wired to the control. No form library: forms are small, server validation arrives as `ApiProblem`, and the shadcn skill does not require one.
- **React 19 and TypeScript.** `<Context value>`/`use()`, `ref` as a prop; `noUncheckedIndexedAccess` on.
- **Security headers and hygiene.** The dot-matrix animation moves out of an inline `<style>` the CSP blocks; nginx adds `Permissions-Policy`; unused dependencies go; knip and a bundle budget run in `pnpm check`; oxfmt sorts Tailwind classes; axe runs in the existing Playwright specs.
- **Kept.** i18n source keys stay in the language they were written in (Vietnamese-first product); no Storybook, visual regression, date library, toast library, React Compiler or commit hooks.

## Verification

`pnpm check` (API and route stability, i18n, lint including `@shadcn/lint`, format, typecheck, unit tests, knip, bundle budget), `pnpm build`, the Playwright suite in CI, and self-reviewed screenshots of the meeting, preview, administration and Sources pages.
