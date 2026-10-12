# Plan — MEM-221 Phone layout of four list pages

- [x] L2-1a agent card: actions in their own row where the screen cannot hover
- [x] L2-1b Library row: two-line name and no inline download below `sm`
- [x] L2-1c provider card: actions wrap under the name and address below `sm`
- [x] L2-1d Meetings: the search on its own row below `sm`
- [x] A phone-width Playwright case per page
- [ ] `pnpm --dir web check` in CI
- [ ] Browser evidence at 1440×900 and 390×844; owner approval before merge
- [ ] Reconcile `.impeccable/critique/issues/README.md` and the roadmap once merged

## Verification — 2026-10-03

- `tsc -b` passes; `oxlint --deny-warnings` and `oxfmt --check` pass on the changed files.
- Playwright, two workers: `agents`, `library-hub`, `models-administration`, `models-discovery` and `meetings` pass (25 cases), including the four new 390 px cases. The agent case runs with touch emulation, since only a screen that cannot hover shows the actions at rest. With the default worker count the first compile of the dev server times out unrelated cases.
- Vitest for `library`, `models` and `agents`: 88 pass; the Library trash case flaked once under load and passed on rerun, as recorded in MEM-215.
- Screenshots at 390×844 from the new cases (`output/playwright/`): `agents-card-phone`, `library-row-phone`, `mobile-provider-card`, `meetings-list-phone`. The 1440×900 screenshots are not taken yet.
- Impeccable `detect` on the four changed components reports nothing.
