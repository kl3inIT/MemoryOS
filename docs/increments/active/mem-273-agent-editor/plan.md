# Implementation plan

Design: [design.md](design.md). Four pull requests, each accepted when CI is green and staging has deployed it; the
owner checks the editor on staging after the third.

## 1. Fields

- [ ] Migration V181: add `title` (80), `greeting` (500), `reasoning_effort`; append a non-empty `task_prompt` to
  `instructions` after a blank line; drop `task_prompt`, `knowledge_cutoff`, `context_token_limit`,
  `output_token_limit`, `replace_base_system_prompt`.
- [ ] Core: `PersonaEntity`, `ChatPersonaService` validation and views, `JdbcChatRepository.persona` and
  `ChatTurnOptions` without the removed fields; `ChatModelExecutor`, `ChatModelGuard`, `ChatTurnSetup` and
  `ChatTurnPersistence` without task prompt, cutoff and per-assistant limits; effort resolved session → assistant →
  member default → model.
- [ ] Defaults: a new assistant gets search and web search only.
- [ ] Greeting and title on the empty conversation (`ChatThread` empty state) and on cards.
- [ ] Current editor: add title, greeting and effort (`ModelSelector` efforts); remove the five fields.
- [ ] Contract: `openapi.yml`, Hey API client.
- [ ] Tests: migration folds a task prompt; effort resolution order; the removed fields no longer reach the prompt;
  persona API round trip of the new fields.

## 2. Editor

- [ ] Shell: tabs with `?tab=`, `ResizablePanelGroup`, sticky section nav, sections per design decision 11.
- [ ] Save stays on the page; disabled-reason tooltip; labels and image applied on Save; blocker kept.
- [ ] Unified document picker with the heading switch; "Chỉ trả lời có căn cứ" folded.
- [ ] Create dialog; `/agents/create` opens it; create then opens the editor.
- [ ] Chia sẻ tab from the share dialog body, with labels and the admin listing switches; the card's share action
  opens the tab.
- [ ] Remove `AgentPreview` and the new-assistant local draft.
- [ ] i18n; tests for tabs, section nav, save states, picker, create flow.

## 3. Test chat

- [ ] Backend: `draftPersona` on the temporary send command, editor-only, validated as Save, no revision check.
- [ ] Web: a single-thread runtime with its own transport inside the editor; greeting, starters, "đang dùng bản chưa
  lưu", "Làm mới"; `Drawer` on phones.
- [ ] Tests: a draft changes the answer without changing the stored assistant; a non-editor is refused; a draft
  source the editor cannot read is refused.

## 4. Channels

- [ ] Migration V182: `channel_connections.persona_id` with `ON DELETE SET NULL`.
- [ ] `ChannelTurns` uses the bound assistant, else the built-in one.
- [ ] `PUT /api/channels/{id}/persona`, channel responses with the assistant, `GET /api/chat/personas/{id}/channels`;
  `CHANNELS_MANAGE` plus edit rights to bind.
- [ ] Kênh tab: list, add, move with confirmation, remove; the admin "Kênh chat" page shows each bot's assistant.
- [ ] Tests: a bound bot answers with its assistant; deleting the assistant falls back; binding without
  `CHANNELS_MANAGE` is refused.

## Verification

- [ ] On zm, throttled: the persona, chat and channel tests, `ModulithArchitectureTest`, `CoreDependencyRulesTest`,
  `OpenApiContractTest`; CI runs the full gates.
- [ ] Staging: create through the dialog, edit and test without saving, save and reopen, share tab, bind a Zalo bot
  and ask it a question. Screenshots in light and dark, desktop and phone.
- [ ] Docs: the chat spec (assistant fields, editor), the channels spec, the test matrices, the roadmap.
