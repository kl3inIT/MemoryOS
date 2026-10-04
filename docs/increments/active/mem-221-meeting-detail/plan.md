# Plan — MEM-221 Meeting detail page

- [x] Wide page, always-visible panel from 1280 px, sheet below; breadcrumb in the shell header
- [x] Sharing in the header; details and deletion in the "…" menu
- [x] Transcript rows by turn: badge, name, words, time; pinned tabs and search
- [x] Voice badges in `speaker-1` … `speaker-6`
- [x] Unsure and corrected words marked in their lines; legend with undo-all; folded proposals
- [x] Search without marks; keyboard reach
- [x] The open tab in the address, without moving the page
- [x] Stars shown at once; failures as notifications
- [x] "Name đây" offered only for a listed participant
- [x] Unit and Playwright cases at 1440 px and 390 px
- [x] Critique of 2026-10-04 (28/40): proposals realigned when their line changes, bulk accept inside the list, guards and help
- [ ] `pnpm --dir web check` and the backend gate in CI
- [ ] Light theme and a live recording reviewed in a browser; owner approval before merge
- [ ] Impeccable critique re-scored after the 28/40 fixes (target above 30/40)

## Verification — 2026-10-04

- `oxlint --deny-warnings`, `oxfmt --check`, `tsc -b`, the i18n audit and `knip` pass; `pnpm build` passes at 313.2 KiB of the 315.4 KiB initial-load budget.
- Vitest for `meetings` (50) and `sources` (112) passes. The whole suite, run on a machine short of memory beside the API and the dev server, timed out in unrelated cases (`agent-editor`, `document-sets-page` and others at the 5 s limit); it is left to CI.
- Playwright, two workers: `meetings` and `meeting-transcript` pass (7 cases) at 1440 px and 390 px. The cases found that naming the tab in the address reset the page's scroll after a jump from the timeline, and that the subject marked as being read ignored what is pinned over the lines; both are fixed.
- `:core:test --tests '*SpeakerIntroductionsTest*'` passes.
- After the 28/40 critique: `:core:test --tests '*MeetingRepositoryTest*' --tests '*MeetingCorrectionSpansTest*'` passes; Vitest `meetings` passes (51); `meetings` and `meeting-transcript` Playwright pass again (7 cases); lint, format, `tsc -b`, the i18n audit and `knip` pass. The correction service's new path through `accept-all` has no API-level case yet.
- Reviewed in the Orca browser, dark theme at 1720 px: turns, badges, marks, the correction popover, the legend, the "…" menu, search and the tab in the address. Undo of one correction and of all were not pressed on real data.
