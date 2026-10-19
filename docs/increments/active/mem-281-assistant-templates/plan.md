# Implementation plan

Design: [design.md](design.md). Three pull requests, each accepted when CI is green and staging has deployed it.

## 1. Templates (backend)

- [x] Eight catalogue files under `memoryos-core/src/main/resources/chat/assistant-templates/` with
  Vietnamese and English text, English instructions, tools, `grounded` and avatar seed ([templates](design.md#templates)).
  Test: every file loads, has both languages and stays inside the assistant limits.
- [x] Migration V188: `persona.template_key` (nullable, 64) with a unique
  index per Tenant over live rows.
- [x] `AssistantTemplateProvisioner`: in the Tenant bootstrap transaction and at startup for every Tenant, insert
  missing templates, rewrite rows that differ, soft-delete templates no longer in the catalogue.
- [x] `AgentAccessSql`: a template row is used only while listed and is never edited or owned; listing allows
  `is_listed` only on a template row; copy endpoint `POST /api/chat/personas/{id}/copy` (`AGENTS_CREATE`,
  `AGENT_NOT_A_TEMPLATE`); views carry `template` and localized text.
- [x] Third-party notice for lobe-chat-agents (MIT).
- [x] Tests: provisioning is idempotent and rewrites only changed rows; a template conversation runs a turn, is pinned
  and shows in recents; a hidden template leaves Khám phá and its conversation cannot continue; edit, delete, share,
  transfer and bot binding are refused; copy in each language is private and attaches no Sources.
- [x] `openapi.yml`, Hey API client; chat spec and test matrix.

## 2. Khám phá (web)

- [x] Templates in "Tất cả" after the built-in and featured assistants, on the existing card naming "MemoryOS"
  without "· Công khai"; ⋯ offers "Tạo bản riêng" (then the copy's editor) and, to agent administrators, "Ẩn với mọi
  người" / "Hiện lại"; the viewer's "Tạo bản riêng" replaces "Sửa"; a hidden template shows only to agent
  administrators, faded, with "Đã ẩn" ([decision 7](design.md#decisions)).
- [x] Every viewer's composer reads "Nhập câu hỏi…".
- [x] The catalogue's unused `category` goes; persona views carry `templateKey`; the model administrator's assistant
  list names templates in the reader's language; `openapi.yml`, Hey API client.
- [x] Tests: `AssistantTemplateIntegrationTest` (list order, the hidden template for administrators, the model list's
  language); e2e for the card, the ⋯ menu as member and administrator, hiding, showing and copying.
- [x] Screenshots light/dark, desktop and phone, reviewed before the pull request.

## 3. Remove `display_priority`

- [ ] Migration dropping `persona.display_priority`; ordering built-in, featured, templates, name; API and web stop passing the
  field; `openapi.yml`, Hey API client.

## Acceptance on staging

- [ ] Each template answers its first starter as a member, and "Hỏi đáp chính sách nội bộ" cites a company policy.
- [ ] An administrator hides a template and shows it again; a copy opens in the editor and stays private.
- [ ] Delete the five test assistants under [Staging cleanup](design.md#staging-cleanup); keep "Benchmark - grounded".
- [ ] Consolidate into `docs/specs/chat.md`, move this increment to `completed/` and reconcile the roadmap.
