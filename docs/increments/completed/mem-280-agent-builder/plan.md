# Implementation plan

Design: [design.md](design.md). Three pull requests, each accepted when CI is green and staging has deployed it.

## 1. The builder turn (backend)

- [x] `ModelFlow.AGENT_BUILDER` and `AiUsageFlow.AGENT_BUILDER`; V190 seeds the flow row for every Tenant (as V172
  did for `CHAT_COMPACTION`) and V191 validates the usage check; the Models page refuses a model without tool calling
  for this flow.
- [x] `ChatSendTurnRequest.builder`: accepted only with `draftPersona`, on a temporary conversation the caller owns,
  for an assistant they may edit, without Web, image, MCP, files or Deep research; otherwise refused.
- [x] Builder turn: the builder system prompt, the form and the person's choices as context (at most 50 of each kind),
  only the builder tools (`update_assistant`, `write_instructions`, `find_options`), the `AGENT_BUILDER` model, usage
  recorded on that flow; the Tenant's guardrails apply, the grounded-answer policy does not.
- [x] Proposals checked with the `draftPersona` rules; an accepted one streams as `assistant_change` (the changed
  fields; the web keeps the previous values), a refused one returns its reason to the model.
- [x] Tests: a builder turn on a non-editor or a kept conversation, or without the form, is refused; a proposal naming
  an unreadable Source or a malformed ID is refused with its reason and changes nothing; accepted fields come back and
  the next call builds on them; no search, web or MCP tool is offered; usage lands on the flow; a message that trips a
  guardrail is blocked like any turn; the task model must call tools.
- [x] `openapi.yml`, Hey API client; chat and chat-models specs, test matrix.

## 2. *Agent Builder* (web)

- [x] The test chat panel's two tabs (*Chat thử*, *✦ Agent Builder*), on desktop and in the phone drawer, the last tab
  remembered per browser.
- [x] The builder thread: its own temporary conversation, `builder: true`, the change card (before → after, the
  instructions as a line count) and *Hoàn tác*, field by field, skipping fields edited since. The two answer buttons
  for a change that asks first are dropped: the server marks no question, so the person answers in the composer
  ([design, decision 5](design.md#decisions)).
- [x] The create dialog: a filled *Mô tả* opens the editor on *Agent Builder* and sends it to the builder.
- [x] Tests: `BuilderChanges` takes a change once and *Hoàn tác* restores all but a field edited since; the transport
  sends a builder turn without the assistant's tools and hands each change on once; the panel's tabs and a viewer
  without them; e2e of a description becoming a filled form, then *Hoàn tác*. *Hủy* leaves the page as before, so
  the builder's unsaved changes go with the rest of the form.
- [x] Screenshots light, dark and phone; chat spec.

## 3. Acceptance on staging

- [x] A new assistant from a description: title, instructions, greeting, starters and tools filled; *Chat thử* answers
  with them; *Lưu* publishes.
- [x] "Trả lời ngắn hơn", "thêm tìm web", "đổi sang model rẻ hơn" (asks first), a refused Source; *Hoàn tác*.
- [x] Close MEM-276 as merged into MEM-280; `verification.md`; roadmap.
- [x] *As accepted:* two prompt fixes came out of acceptance, #802 and #804 ([design, decision 6](design.md#decisions));
  the results are in [verification.md](verification.md).
