# Plan — MEM-213 copy and terms

- [x] Tone style normalized; duplicate English-catalog keys merged; `check:i18n` guard (proved to flag *Xoá*)
- [x] Chat, owner role, Back to the app, Sources page name, Users search label
- [x] `tsc -b`, `oxlint`, `format:check`, `check:i18n`, `test:unit` (170 files, 831 tests) pass
- [x] Playwright: agents, chat-workspace, file-source-setup, identity-shell, mcp-administration, meetings, models-administration, search-settings (74 passed locally, 2026-10-03)
- [x] Sources list follow-up: access as plain text with its icon; group rows without counts; three-figure strip with a failed filter tile; stat labels wrap (Sources e2e, `source-list.test.tsx` filter case)
- [ ] Browser evidence of the renamed labels (`/admin`, `/admin/users`, Chat home at 1440×900, no overflow); owner approval before merge
- [x] Move `mem-212-source-list-status` to `completed/` and reconcile the roadmap
