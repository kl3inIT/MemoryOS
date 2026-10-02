# Plan — MEM-212 Sources list and status

- [x] Status presentation: unknown status → neutral *Unknown*; access presentation without tones
- [x] `SourceStatusBadge` soft; `SourceAccessBadge` outline `Badge` with icon
- [x] `SourceList`: `StatStrip` summary, single header, slim group rows, failures first, failure reason, container-query compact layout
- [x] Load failure copy *Couldn't load sources* on the admin page and the member library
- [x] Users table: hide Groups and Account type below `md`, no minimum width on phones
- [x] i18n keys and Vietnamese copy
- [x] Unit tests for the list's observable contract; update `identity-shell.spec.ts` for the new group row and summary
- [ ] `pnpm --dir web check` (CI if local memory is short)
- [ ] Browser evidence: `/admin` and Users at 1440×900 and 390×844, light and dark; owner approval before merge
- [x] Consolidate durable facts (Sources spec / design tokens) after verification

## Verification — 2026-10-02

- `tsc -b`, `oxlint --deny-warnings .`, `check:i18n` and `oxfmt` on the changed files pass.
- `source-list.test.tsx` fails on the previous code (row order) and passes; `readable-sources` and `users-table` unit tests pass.
- Playwright `identity-shell.spec.ts`: *creates, indexes, removes, and deletes a FILE source* and *manages members and one-time invitation recovery* pass, including the new 390 px checks.
- Browser screenshots of `/admin` at 1440×900 (light, dark), 1024×768 and 390×844, and `/admin/users` at 1440×900 and 390×844: no horizontal page overflow at any size. The first round truncated two figure labels on phones; *Open to all members* and *Total docs* replace them, and the list's `documentsTotalLabel` prop, now always equal to `documentsLabel`, is removed.
- Impeccable `detect` on the changed components reports nothing.
