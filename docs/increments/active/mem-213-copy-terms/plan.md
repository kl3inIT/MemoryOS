# Plan — MEM-213 copy and terms

- [x] Tone style normalized; duplicate English-catalog keys merged; `check:i18n` guard (proved to flag *Xoá*)
- [x] Chat, owner role, Back to the app, Sources page name, Users search label
- [x] `tsc -b`, `oxlint`, `format:check`, `check:i18n`, `test:unit` (170 files, 831 tests) pass
- [x] Playwright: agents, chat-workspace, file-source-setup, identity-shell, mcp-administration, meetings, models-administration, search-settings (74 passed locally, 2026-10-03)
- [x] Sources list follow-up: access as plain text with its icon; group rows without counts; figure strip icons and colours (Sources e2e passes)
- [ ] Browser evidence of the renamed labels (`/admin`, `/admin/users`, Chat home at 1440×900, no overflow); owner approval before merge
- [x] Move `mem-212-source-list-status` to `completed/` and reconcile the roadmap
