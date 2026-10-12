# Plan — One AI Providers page

- [x] `/admin/ai-providers` route with the `tab` search parameter and the Models preload
- [x] `AiProvidersPage`: one header, five tabs, each tab loaded when opened
- [x] The five pages lose their own layout and header; *Refresh catalog* moves into the Models tab
- [x] One `aiProviders` row in `admin-pages.ts`; the administration entry lands on it
- [x] The five old addresses redirect to their tab; in-app links use the new address
- [x] Specs and unit tests follow the new address, heading and menu row
- [x] Architecture, Chat and Chat models documents name the new address
- [ ] Owner review; `pnpm --dir web check` in CI
- [ ] Move to `completed/` and reconcile the roadmap once merged

## Verification — 2026-10-10

- `oxlint --deny-warnings`, `oxfmt`, `tsc -b`, the i18n audit and knip pass. `pnpm build`: initial load 314.0 of 315.4 KiB gzip.
- Vitest, whole suite: 859 of 862 before the two link expectations in `meeting-minutes.test.tsx` were updated; they pass after. The remaining failure, `library-page.test.tsx` "says a deleted file goes to the trash…", fails and passes between runs with and without this change.
- Playwright, one worker: `voice-administration`, `system-one-administration`, `models-administration`, `models-discovery`, `chat-ui-polish` and `identity-shell`, 36 of 36. `voice-administration` opens `/admin/voice` and asserts the redirect to `?tab=voice`. The whole suite has not run.
- Browser evidence: the Voice tab at 1440×900 and 390×844 on the spec's fixtures. On the phone the open tab sat outside the tab row; the row now scrolls it into view, which is not recaptured.
