# MEM-280 Agent Builder: chat with AI to change an assistant

The person who edits an assistant can ask an AI, in the panel beside the editor, to change it over several turns:
"trợ lý này trả lời về chính sách nhân sự", "trả lời ngắn hơn", "thêm tìm web", "đổi sang model rẻ hơn". The AI
changes the editor's unsaved form, shows what it changed, and *Lưu* still publishes. It replaces the one-shot buttons
MEM-276 proposed ("Điền giúp", "Viết giúp", "Gợi ý thêm").

The owner asked on 2026-10-19 to build the multi-turn builder directly ("làm luôn cái agent builder đi").

## Today

- The editor `/agents/{id}/edit` holds one unsaved form (`agent-form.ts`, `AgentValues`): name, title, description,
  avatar, instructions, greeting, up to four starters, Sources, Document Sets, files, tools (`search`, `web_search`,
  images, Sandbox), MCP servers, model, reasoning effort and `grounded`. *Lưu* sends it; *Hủy* drops it; the header
  says *Có thay đổi chưa lưu* (chat spec, "Agents").
- Beside it, from `lg`, is the test chat (`agent-test-chat.tsx`): the real `ChatThread` in its own runtime, a
  temporary conversation of the assistant, every question sending the form as `draftPersona`. The server accepts a
  draft only from someone who may edit the assistant and checks it as *Lưu* checks it (an unreadable Source, an
  unavailable Document Set, MCP server or model is `CHAT_INVALID_REQUEST`). On phones the test chat is a drawer
  opened by a floating *Chat thử* button.
- The create dialog (`agent-create-dialog.tsx`) takes name, title and description, saves the assistant and opens its
  editor. Templates (MEM-281) reach the editor through *Tạo bản riêng*.
- Side tasks run on task models (`ModelFlow`: `CHAT_NAMING`, `CHAT_GUARDRAIL`, `CHAT_COMPACTION`, meeting flows),
  set per Tenant on the Models page and falling back to the conversation model (chat-models spec, "Task models").
- Since MEM-281 follow-ups, every member's Basic Group may create assistants (`AGENTS_CREATE`).

## How the references do it

| | Where | What the AI changes | Saved how | Shows the change | Asks first | Verified |
| --- | --- | --- | --- | --- | --- | --- |
| **LobeHub** Agent Builder | Right panel of the agent profile (`src/features/AgentBuilder`, `RightPanel`) | `updateConfig` (model, plugins, opening message and questions, chat settings, name, title, description, avatar, tags), `updatePrompt` (streamed), `installPlugin`; reads `getAvailableModels`, `searchMarketTools` (`builtin-tool-agent-builder/src/manifest.ts`) | Written at once by the server runtime; the store refreshes (`executor.ts`, `onAfterCall`) | A before → after card per call (`ConfigDiffView`, `PromptDiffView`) | Installing a plugin always needs approval (intervention card); the prompt says to check before switching model or disabling a plugin the agent relies on | Code |
| LobeHub context | The current configuration is injected as `<current_agent_context>`; the builder never asks for it (`systemRole.ts`) | | | | | Code |
| LobeHub stays in role | A message that reads like a domain question is a description of the agent to build (`systemRole.ts`, `<role>`) | | | | | Code |
| LobeHub conversations | Kept as topics with a picker (`TopicSelector.tsx`) | | | | | Code |
| LobeHub create | "What should this Agent do?" creates the agent and sends the sentence to the builder (`useCreateModal.tsx`) | | | | | Code (2026-10-16) |
| **Glean** Agent Builder | A natural-language panel opened from the agent editor ("Open panel"); an Assistant-guided flow drafts the agent on the right | Steps, model, triggers | Natural-language and manual edits mix freely; publish after testing | Not stated | Not stated | Docs |
| **Copilot Studio** | *Describe* tab beside *Configure* | Name, description, instructions, knowledge | A draft; the two tabs work on the same draft | Fields update | Not stated | Docs |
| **ChatGPT GPT builder** | Conversational builder beside a configuration view | Name, instructions, starters, capabilities | Draft until Create/Update | Fields update | Not stated | Help centre (article blocked, 403); known, not re-checked |
| **Dify** | *Automatic* button next to the prompt (`config/automatic`) | The prompt only, from an instruction | Versions to pick, then applied | Version selector | n/a | Code |
| **Coze** | Auto-generate buttons on the prompt and tools (`auto-generate-btn`) | One field | Confirm before overwriting | n/a | Confirm | Code |
| GoClaw, OpenClaw | One-shot generation from a description (summoning), a custodian chat that proposes a template | | | | | Issue MEM-276 (code read 2026-10-16) |
| **Onyx** | None (no builder or prompt generator in `web/src` or `backend/onyx` file names) | | | | | Code |

Onyx, our first reference, has no builder. Of the products that do, LobeHub is the only open one, and its design is
the one this follows; Glean, Copilot Studio and the GPT builder settle the one point where LobeHub differs from
MemoryOS's editor: whether the AI's change is saved at once.

## Decisions

1. **The builder lives in the test chat's panel, as a second tab: *Chat thử* | *✦ Nhờ AI chỉnh*.** LobeHub puts the
   builder in the profile's right panel; Glean opens a panel beside the editor. MemoryOS already has that panel, so no
   new pane: the header's title becomes a two-tab switch, the badge *Đang dùng bản chưa lưu* stays, and on phones the
   same drawer opens with the same two tabs. The panel remembers the last tab per browser.
2. **Changes go into the unsaved form; *Lưu* publishes them.** Glean, Copilot Studio and the GPT builder change a
   draft that is published separately; LobeHub writes at once, but LobeHub's editor saves every field at once too.
   MemoryOS's editor has *Lưu* and *Hủy*, so the builder's changes are unsaved changes like the person's own: the
   header says *Có thay đổi chưa lưu*, *Hủy* drops them, *Chat thử* already answers with them. Nothing the builder
   does reaches other people before *Lưu*.
3. **Each change shows a card of before → after, with *Hoàn tác*.** As LobeHub's `ConfigDiffView`: one card per
   change, one row per field (title, greeting, each starter added or removed, each tool or Source turned on or off,
   model, `grounded`); the instructions show as "Viết mới · n dòng" or "Sửa n dòng", the full text being in the form
   the person sees. *Hoàn tác* puts those fields back to their values before the change, field by field, and skips a
   field the person has edited since. LobeHub has no undo because it saves at once; with a draft, undo is cheap and
   makes trying a suggestion safe.
4. **What the builder may change: the *Cấu hình* tab only.** Name, title, description, avatar seed, instructions,
   greeting, starters, document search and `grounded`, Sources, Document Sets, the web, image and Sandbox tools, MCP
   servers, model and reasoning effort. Never sharing, labels, *Hiện ở Khám phá*, *Nổi bật*, channels, ownership or
   deletion: those are not in its tools at all. It may only choose what the editing person may choose: the models of
   the assistant's model list, Sources the person can read, Document Sets and MCP servers available to them, the same
   check as `draftPersona`. Files are left to the person (uploading needs their file).
5. **Ask before a change that removes something or costs something.** LobeHub's prompt checks before switching model
   or disabling a plugin the agent relies on, and always asks before installing. The builder asks in its reply, with
   two answer buttons, before: switching the model, turning off document search, the web or an MCP server, removing a
   Source or Document Set, or turning `grounded` off. Everything else it changes directly; the card and *Hoàn tác*
   cover it.
6. **Two tools, like LobeHub: `update_assistant` and `write_instructions`.** `update_assistant` takes any subset of
   the fields of decision 4 in one call (LobeHub batches related edits in one `updateConfig`); `write_instructions`
   takes the whole new instructions, written last so they can name the tools now on (LobeHub's order: identity, then
   model and tools, then the prompt). The server checks every proposal with the `draftPersona` rules and answers the
   tool with what was accepted or why not, so the model can explain a refused Source; it never writes the assistant.
   The browser applies the accepted change to the form and draws the card.
7. **The builder reads the form, not the saved assistant.** As LobeHub injects `<current_agent_context>`, each turn
   sends the form as `draftPersona` (the test chat already does) and the server gives the builder the current values
   with the choices the person has: model list, the Sources, Document Sets and MCP servers they may add (names and
   ids, at most 50 of each, with a `find_options` tool for the rest, as LobeHub's `getAvailableModels`). The builder
   never asks for what it can read.
8. **It stays a builder.** LobeHub's rule: a message that reads like a domain question is a description of the
   assistant to build. "Nghỉ phép năm bao nhiêu ngày?" in *Nhờ AI chỉnh* becomes a starter or an instruction, not an
   answer; to try the assistant, *Chat thử* is one tab away, and the builder says so.
9. **A task model, `AGENT_BUILDER`.** It runs on its own `ModelFlow` row, set on the Models page and falling back to
   the Tenant's Chat default like every task, not on the assistant's model: the assistant may be set to a small model
   the builder should not depend on, and changing the assistant's model must not change who edits it. It needs tool
   calling; a task model without it is refused on the Models page for this flow. Usage is recorded as the
   `AGENT_BUILDER` flow, charged to the editing person.
10. **Builder conversations are not kept.** Like the test chat, each is a temporary conversation of the assistant,
    gone when the editor closes or *Làm mới* is pressed. LobeHub keeps builder topics, but what matters survives in the
    form and then the saved assistant; keeping them would add a second history nobody reads. A builder conversation
    never appears in the sidebar, *Gần đây* or history.
11. **The create dialog hands its description to the builder.** As LobeHub's "What should this Agent do?": when the
    dialog's *Mô tả* is filled, the new assistant's editor opens on *Nhờ AI chỉnh* and sends "Dựng trợ lý theo mô tả
    này" with the description, so the first answer already fills title, instructions, greeting, starters and tools. An
    empty description opens the editor as today. The dialog keeps its three fields.
12. **MEM-276's buttons are not built.** Dify's *Automatic* and Coze's auto-generate buttons are single-field helpers;
    with the builder beside the form, "viết lại hướng dẫn" or "gợi ý thêm câu hỏi" is one sentence in *Nhờ AI chỉnh*,
    and two ways to do the same thing is what the owner asks us to avoid. MEM-276 closes as merged into MEM-280.
13. **Templates need nothing extra.** *Tạo bản riêng* opens the copy's editor, where the builder works like on any
    assistant; a template itself has no editor.
14. **Who may use it:** whoever may edit the assistant (the `draftPersona` rule), so members with `AGENTS_CREATE` on
    their own assistants; a viewer never sees the tab.

## The builder's turn

- `sendChatMessage` on the temporary conversation with `draftPersona` and `builder: true`. The server runs it on the
  `AGENT_BUILDER` model with a fixed builder system prompt (English, MemoryOS's own, after LobeHub's role, workflow
  and guidelines), the current form and choices as context, and only the builder tools: no document search, web,
  Sandbox or MCP, so the builder cannot be steered by a document or page, and no assistant instructions.
- Tool results stream as ordinary tool events; the web renders `update_assistant` and `write_instructions` as the
  change card instead of the activity row, applies the accepted values to the form, and marks the card applied.
- A refused proposal (an unreadable Source, a model not in the list, a field over its limit) returns its reason to
  the model, which tells the person; nothing reaches the form.
- The builder answers in the person's language; the instructions it writes follow the assistant's language as the
  person describes it (Vietnamese by default for a Vietnamese account).

## API

| Change | What |
| --- | --- |
| `ChatSendTurnRequest.builder` | Optional boolean, with `draftPersona` required, only on a temporary conversation the caller owns, for an assistant they may edit; otherwise `CHAT_INVALID_REQUEST` |
| Tool events `update_assistant`, `write_instructions` | Carry `accepted` (the field values), `previous` and `refused` (field and reason) |
| `ModelFlow.AGENT_BUILDER` | A new task row (migration seeds it for every Tenant like `CHAT_COMPACTION`, V172) |
| `AiUsageFlow.AGENT_BUILDER` | Usage of builder turns |

## Not in scope

- Uploading files through the builder, generating avatars from images, sharing and channel routing.
- Keeping builder conversations; a builder for templates themselves.
- A builder outside the editor (for example from the chat page).

## Risks

- **A wrong change goes unnoticed.** Every change is a card with before → after and *Hoàn tác*, nothing is published
  before *Lưu*, and *Chat thử* answers with the draft at once.
- **Prompt injection through the person's own words or a Source name.** The builder has no search, web or MCP tools,
  and every proposal passes the same server checks as *Lưu*, so it can only choose what the person could choose.
- **Cost.** One task-model call per builder turn plus its tool round; on staging the task model is `cx/gpt-6-luna`
  through 9Router.
