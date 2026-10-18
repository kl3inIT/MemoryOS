# Implementation plan

Design: [design.md](design.md). Three pull requests, each accepted when CI is green and staging has deployed it.

## 1. Templates and copying (backend)

- [ ] Eight template files under `memoryos-core/src/main/resources/chat/assistant-templates/`, Vietnamese and English,
  with the tools, `grounded`, avatar seed and `basedOn` of the [shortlist](design.md#shortlist).
- [ ] Loader validated at startup against the assistant limits and the tool set. Test: every file loads, has both
  languages and stays inside the limits.
- [ ] Migration (the next free version when pushed, V188 today): `persona.template_key` (nullable, 64).
- [ ] `GET /api/chat/personas/templates` and `POST /api/chat/personas/templates/{key}` (`AGENTS_CREATE`), with
  `AGENT_TEMPLATE_NOT_FOUND`. Contract records under `contract/`. `templateKey` on the persona view.
- [ ] Third-party notice for lobe-chat-agents (MIT).
- [ ] Tests: copy in each language, `usedBy`, permission, unknown key; the copy is private and attaches no Sources.
- [ ] `openapi.yml`, Hey API client; chat spec and test matrix.

## 2. Create dialog (web)

- [ ] "Tạo trợ lý" opens on "Trống" or the template grid; preview of greeting and starters; "Dùng mẫu" creates the
  copy and opens the editor; "Đã dùng" with "Mở" and "Dùng lại".
- [ ] The empty "Của tôi" view links to the grid.
- [ ] i18n; unit tests for the grid states; e2e for copying a template.
- [ ] Screenshots light/dark, desktop and phone, reviewed before the pull request.

## 3. Remove `display_priority`

- [ ] Migration dropping `persona.display_priority`; ordering builtin, featured, name; API and web stop passing the
  field; `openapi.yml`, Hey API client.

## Acceptance on staging

- [ ] Copy each template as a member and ask its first starter; the answer follows the template.
- [ ] Delete the five test assistants listed under [Staging cleanup](design.md#staging-cleanup); keep "Benchmark -
  grounded".
- [ ] Consolidate into `docs/specs/chat.md`, move this increment to `completed/` and reconcile the roadmap.
