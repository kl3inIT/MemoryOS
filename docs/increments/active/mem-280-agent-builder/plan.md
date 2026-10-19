# Implementation plan

Design: [design.md](design.md). Three pull requests, each accepted when CI is green and staging has deployed it.

## 1. The builder turn (backend)

- [ ] `ModelFlow.AGENT_BUILDER` and `AiUsageFlow.AGENT_BUILDER`; the next free migration seeds the flow row for every
  Tenant (as V172 did for `CHAT_COMPACTION`); the Models page refuses a model without tool calling for this flow.
- [ ] `ChatSendTurnRequest.builder`: accepted only with `draftPersona`, on a temporary conversation the caller owns,
  for an assistant they may edit; otherwise `CHAT_INVALID_REQUEST`.
- [ ] Builder turn: the builder system prompt, the form and the person's choices as context (at most 50 of each kind),
  only the builder tools (`update_assistant`, `write_instructions`, `find_options`), the `AGENT_BUILDER` model, usage
  recorded on that flow.
- [ ] Proposals checked with the `draftPersona` rules; tool results carry `accepted`, `previous`, `refused`.
- [ ] Tests: a builder turn on a non-editor or a kept conversation is refused; a proposal naming an unreadable Source or
  a model outside the list is refused with its reason; accepted fields come back with their previous values; no
  search, web or MCP tool is offered; usage lands on the flow.
- [ ] `openapi.yml`, Hey API client; chat and chat-models specs, test matrix.

## 2. *Agent Builder* (web)

- [ ] The test chat panel's two tabs (*Chat thử*, *✦ Agent Builder*), on desktop and in the phone drawer, the last tab
  remembered per browser.
- [ ] The builder thread: its own temporary conversation, `builder: true`, the change card (before → after, the
  instructions as a line count) and *Hoàn tác*, field by field, skipping fields edited since; the two answer buttons
  for a change that asks first.
- [ ] The create dialog: a filled *Mô tả* opens the editor on *Agent Builder* and sends it to the builder.
- [ ] Tests: the card applies to the form and *Hoàn tác* restores; *Hủy* drops builder changes; a viewer has no tab;
  e2e of a description becoming a filled form.
- [ ] Screenshots light, dark and phone; chat spec.

## 3. Acceptance on staging

- [ ] A new assistant from a description: title, instructions, greeting, starters and tools filled; *Chat thử* answers
  with them; *Lưu* publishes.
- [ ] "Trả lời ngắn hơn", "thêm tìm web", "đổi sang model rẻ hơn" (asks first), a refused Source; *Hoàn tác*.
- [ ] Close MEM-276 as merged into MEM-280; `verification.md`; roadmap.
