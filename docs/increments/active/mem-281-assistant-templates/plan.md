# Implementation plan

Design: [design.md](design.md). Three pull requests, each accepted when CI is green and staging has deployed it.

## 1. Templates (backend)

- [ ] Eight catalogue files under `memoryos-core/src/main/resources/chat/assistant-templates/` with category,
  Vietnamese and English text, English instructions, tools, `grounded` and avatar seed ([templates](design.md#templates)).
  Test: every file loads, has both languages and stays inside the assistant limits.
- [ ] Migration (the next free version when pushed, V188 today): `persona.template_key` (nullable, 64) with a unique
  index per Tenant over live rows.
- [ ] `AssistantTemplateProvisioner`: in the Tenant bootstrap transaction and at startup for every Tenant, insert
  missing templates, rewrite rows that differ, soft-delete templates no longer in the catalogue.
- [ ] `AgentAccessSql`: a template row is used only while listed and is never edited or owned; listing allows
  `is_listed` only on a template row; copy endpoint `POST /api/chat/personas/{id}/copy` (`AGENTS_CREATE`,
  `AGENT_NOT_A_TEMPLATE`); views carry `template` and localized text.
- [ ] Third-party notice for lobe-chat-agents (MIT).
- [ ] Tests: provisioning is idempotent and rewrites only changed rows; a template conversation runs a turn, is pinned
  and shows in recents; a hidden template leaves Khám phá and its conversation cannot continue; edit, delete, share,
  transfer and bot binding are refused; copy in each language is private and attaches no Sources.
- [ ] `openapi.yml`, Hey API client; chat spec and test matrix.

## 2. Khám phá (web)

- [ ] "Mẫu của MemoryOS" at the top of "Tất cả", grouped by category; a card opens a new conversation; the viewer
  offers "Trò chuyện" and "Tạo bản riêng"; agent administrators get "Ẩn khỏi Khám phá" / "Hiện ở Khám phá" on a
  template and no edit actions.
- [ ] i18n; unit tests for the grouped list, the hidden state and the template viewer; e2e for chatting with a
  template and making a copy.
- [ ] Screenshots light/dark, desktop and phone, reviewed before the pull request.

## 3. Remove `display_priority`

- [ ] Migration dropping `persona.display_priority`; ordering built-in, featured, name; API and web stop passing the
  field; `openapi.yml`, Hey API client.

## Acceptance on staging

- [ ] Each template answers its first starter as a member, and "Hỏi đáp chính sách nội bộ" cites a company policy.
- [ ] An administrator hides a template and shows it again; a copy opens in the editor and stays private.
- [ ] Delete the five test assistants under [Staging cleanup](design.md#staging-cleanup); keep "Benchmark - grounded".
- [ ] Consolidate into `docs/specs/chat.md`, move this increment to `completed/` and reconcile the roadmap.
