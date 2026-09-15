# Plan

- [x] Trace the supplied wordmark to SVG and replace the raster splash with the CSP-compatible intro and exit.
- [x] Redirect signed-out browsers directly to the backend OAuth2 flow with the redirect-loop guard.
- [x] Add `BrandLoader` and apply it to full-page and page-level primary loading states.
- [x] Verify the first version (gates, dev runtime, identity browser spec) and consolidate the identity spec and verification matrix.
- [x] Owner clarification: split the full intro (a tab's first load) from the short sheen form (reload, return from sign-in, loader); delay the sign-in redirect until the splash has finished.
- [x] Re-verify gates, both splash modes, the loader sheen, the delayed redirect and the identity browser spec.
- [ ] Owner acceptance; commit, PR and Linear remain owner decisions.

## Verification — 2026-09-14/15

Local Node is 22.13 while the repository engine requires 24 or later, so scripts that import TypeScript ran with `--experimental-strip-types`.

- `oxfmt --check`, `oxlint --deny-warnings`, `tsc -b` and the i18n audit pass. The full unit suite passes: 44 files, 241 tests, including `boot-splash.test.ts` (per-mode intro time, exit-event filtering, fallback removal) and the delayed redirect in `session-states.test.tsx`.
- `vite build` succeeds. The splash and loader CSS are in the linked entry stylesheet; the built `index.html` has no `<style>` element, style attribute or inline script, and `splash-mode.js` is emitted at the root (served with the default `no-cache` policy).
- `identity-shell.spec.ts` run serially: 15 of 15 pass. Earlier, before the split, one serial run had a member-navigation timeout that passed in 6 of 6 repeats, and parallel runs against the cold dev server timed out on route pending; neither is counted as evidence.
- Dev runtime, driven with Playwright on the Vite server:
  - A new tab selects `full`; signed out, the exit starts at 1.26 s and the OAuth2 navigation happens at 2.49 s, when the splash is removed.
  - Reloading the same tab selects `short`; signed out, the exit starts at 1.55 s, the splash is removed at 1.85 s and the OAuth2 navigation follows at that moment.
  - Seeked frames show the ribbon draw-in, then the repeating sheen on both the short splash and the loader, with a brand-blue sheen in dark theme.
- First version, still applicable: a reload inside the redirect window shows the manual gate without a second navigation; reduced motion runs no loader animation; the loader renders inside the pending Sources page.
