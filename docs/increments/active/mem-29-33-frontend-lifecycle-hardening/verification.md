# MEM-29–33 verification: frontend lifecycle hardening

## Static and build gates

- TypeScript LSP diagnostics reported no issues in edited routes, shell/UI components, identity/session files, invitation files, tests, or `vite.config.ts`.
- No Java, Kotlin DSL, YAML, properties, or XML files changed, so per-file JetBrains JVM/config inspection was not applicable.
- `pnpm check` passed on the reviewed tree: generated API stability, Playwright image availability, zero-warning lint, formatting, TypeScript, 25 unit tests, generated route-tree stability, and the production Vite build.
- The production-output assertion found four emitted WOFF2 assets and zero inline font data URLs; focused fixtures cover both `font/woff2` and `application/font-woff2` MIME forms inside `@font-face`.
- `gradlew.bat clean check --no-daemon` passed all `core`, `api`, and `worker` checks with 17 actionable tasks.

## Browser regression suite

`pnpm test:e2e` passed all 14 Chromium scenarios. Changed-contract coverage proves:

- internal app/admin/invitation links keep the same browser document;
- route navigation adds no `/api/identity/me` request caused by document replacement;
- the collapsed admin shell persists across `/admin` → `/admin/invitations`;
- the controlled mobile navigation dialog closes after both sidebar and account-menu route changes;
- member deep links remain denied and issue zero invitation requests;
- two same-task invitation form submissions issue exactly one POST and retain the successful one-time link.

## Direct runtime smoke

A real Chromium session exercised the Vite application at `http://127.0.0.1:4173` with same-origin API responses intercepted at the browser boundary:

- a `window` document sentinel survived `/` → `/admin` → `/admin/invitations`;
- identity request count was unchanged across those route transitions (`2` before, `2` after; delta `0`; the second initial request was the intentional foreground synchronization);
- collapsed administration shell state survived the child navigation;
- two synchronous `SubmitEvent`s emitted one invitation POST and rendered the issued link;
- the exercised desktop flow emitted no browser console errors;
- at 390×844, navigation left zero `[role=dialog]` elements and the trigger returned to `aria-expanded="false"` after route change.

## Review evidence

CodeRabbit reviewed head `9e4d8ecd25c85e2ff7f24289bb0c80095ba01260` and raised two valid minor findings. Head `6bbc3f31a2857715c2929911e9116062d29be359` broadens the font assertion to any data URL inside `@font-face`, adds both legacy/current WOFF2 MIME fixtures, and closes the mobile drawer after account-menu navigation. Both threads contain fix evidence and are resolved; `pnpm check` and all 14 Playwright scenarios passed after the fixes.

## Remaining delivery evidence

The increment remains active until the single PR covering MEM-29 through MEM-33 is reviewed and merged. Exact main-SHA CI and staging smoke are post-merge evidence and will be recorded in Linear under the repository operating policy.
