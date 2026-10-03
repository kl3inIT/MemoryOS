# Plan — MEM-221 One pattern per list page

- [x] L2-4b, L2-4d Sources and Users: primary button size and icon, Sources page icon
- [x] L2-n2 binary sizes read GB
- [x] L2-n10 one `h1` on the chat page
- [x] L2-3a, L2-3b administration menu folds; *Thêm nguồn* leaves it
- [x] L2-4c, L2-n1 two date shapes and locale numbers
- [x] L2-5a, L2-5c, L2-5d copy: one name for a conversation, destructive actions say what is lost
- [x] L2-4a, L2-n8 Meetings and Users filters are the registry select; Meetings filters in the address
- [x] L2-2a, L2-n4 Library toolbar: search, filter, one display menu; storage only when nearly full; no toolbar on an empty view
- [x] L2-n3, L2-n6 Models: *Thêm kết nối* on a connected preset; brand loader and skeleton rows
- [x] L2-n5, L2-n7 Web search says why it is off; Users drops a column every row repeats
- [x] L2-n9 finger-sized tabs, view choices, selects and filter chips
- [x] Critique 3: administration menu open while it fits and remembered; search mode in the search field
- [x] Critique 3: Meetings opens with every meeting; shared page-size control; Library on a phone
- [x] Critique 3, the rest: Users row menu as the shared menu; Meetings drops the ended badge and leads to the live meeting; Models guidance and tooltips; a finger-sized image chip
- [x] Critique 4: one height for a select and a field; the Library row menu always visible; a stat tile marked as a filter; the search shortcut shown; a focus ring on project links; no date under a day heading
- [ ] L2-2b meeting detail: left to the transcript work in flight
- [ ] L2-5b conversation count: needs a total from the API
- [ ] Owner review of each direction in the design; `pnpm --dir web check` in CI
- [ ] Reconcile `.impeccable/critique/issues/README.md` and the roadmap once merged

## Verification — 2026-10-03

- `pnpm --dir web check`: API client and route tree stable, i18n audit 0, knip, `oxlint --deny-warnings`, `oxfmt --check`, `tsc -b` and the build budget (313.0 of 315.4 KiB gzip) pass. Vitest: 848 of 849 pass; `mcp-endpoint-admin-page.test.tsx` "adds an app by its domains…" fails here and on `main` alike.
- Playwright, two workers, the whole suite: 201 pass, 1 skipped. New cases: the Meetings filters survive a reload (`meetings.spec.ts`), a tab and a view choice are 44 px on a touch screen (`library-hub.spec.ts`). Changed cases: the Users filter reads its text, the phone administration menu opens *Organization* before *Users*, the 9Router preset reads *Thêm một kết nối 9Router nữa*.
- Vitest cases added or changed: the administration menu folds every section but the open page's (`app-shell.test.tsx`); the storage figures stay in the settings until the limit is near, and an empty Library has no toolbar (`library-page.test.tsx`); the Web note names the model (`chat-web.test.tsx`); the Users table leaves out the repeated column (`users-table.test.tsx`).
- Impeccable `detect` on the changed components reports nothing.
- Third critique (2026-10-03, dual-agent): 25/40, detector clean; snapshot under `.impeccable/critique/`. Its five priority findings are addressed in the last five commits; the app is not rescored after them.
- Fourth critique (2026-10-03, dual-agent, after the third's remaining fixes): 27/40, detector clean. Its findings on control heights, hidden row actions, unmarked filter tiles and the unshown shortcut are addressed after it; the app is not rescored after them. Whole Playwright suite after those changes: 200 pass, 1 skipped, and the one failure (the search trigger's accessible name took the shortcut text) is fixed and its spec passes. Vitest: 847 of 849; both failures are in `mcp-endpoint-admin-page.test.tsx`, one of which passes alone.
- Browser evidence: the same synthetic fixtures before (`1f9f6f989`) and after, at 1440×900 and 390×844, for Users, Meetings, the Library with files and empty, Models and the administration menu. The Sources page and the composer's Web row are not captured.
