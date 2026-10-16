# MEM-273 Assistant editor

The assistant editor becomes one page with three tabs: Cấu hình, Kênh and Chia sẻ. The configuration sits on the
left and a test chat on the right that runs the unsaved form. A Save button keeps the person on the page. The fields
are standardised against the references: three are added and five are removed. This is part of
[MEM-273](https://linear.app/memory-os/issue/MEM-273), after [avatars](../mem-273-agent-avatars/design.md) and
[the assistant sidebar](../mem-273-agent-roster/design.md). "Điền giúp" (MEM-276) and the Agent Builder (MEM-280)
follow it.

The owner settled the decisions on 2026-10-16, question by question, from the research and the prototype reviewed in
chat. Each decision names the references that support it.

## Today

- `/agents/create` and `/agents/$agentId/edit` render one long form (`web/src/features/agents/agent-editor.tsx`) with
  five sections: Thông tin chung, Hướng dẫn, Tri thức, Công cụ, Nâng cao. There are no tabs and no section nav.
- The right column is a static card that says "Lưu trợ lý rồi bấm Bắt đầu chat để thử" (`agent-preview.tsx:83`). It
  is hidden below `lg`. Saving navigates to `/agents` (`use-agent-editor.ts:94`), so every test means leaving the page.
- Sources and document sets are two pickers in Tri thức; files are a third control. The "search documents" switch sits
  in Công cụ, so sources can be chosen with search turned off and no warning.
- The model, `grounded` and "Thay system prompt" hide under Nâng cao. Labels and avatar uploads take effect before
  Save, so Cancel does not undo them. Save is disabled without saying why. New assistants start with all four tools on.
- Sharing is a separate dialog opened from the card (`agent-share-dialog.tsx`); listed and featured are on the admin
  page.
- Every Zalo and Lark bot answers with the built-in assistant (`ChannelTurns.ask` → `defaultPersona`);
  `channel_connections` has no assistant column.
- Reasoning effort is chosen per conversation (`chat_session.reasoning_effort`), then the member default; an
  assistant cannot set its own.

## How the references do it (code read 2026-10-16)

Clones under `tmp/refs`. Dify, Coze and FastGPT are app builders; LobeHub, Onyx, Rakazo, Hermes, GoClaw and OpenClaw
are assistant products.

| | Layout | Test chat | Save | Tabs | Create |
| --- | --- | --- | --- | --- | --- |
| Dify | Config left, debug chat right; drawer on phones (`configuration-view/index.tsx`) | Runs the draft config | Draft, Publish, Restore | — | Template or blank |
| Coze Studio | Build sheet, Preview & Debug sheet (`single-mode/index.tsx`) | Runs the draft | Autosave, Publish | — | Template or blank |
| FastGPT | Form left, `ChatTest` right (`SimpleApp/Edit.tsx`) | Sends the unsaved app with each test message | Save draft, Publish, history | Edit, Publish, Logs | Template or blank |
| LobeHub | Profile document, Agent Builder chat right (`ProfileEditor`) | None; Builder edits | Autosave, edit lock | Profile, Channel, Statistics, Share (`AgentProfileTabs`) | One sentence, then the Builder |
| Onyx | One long form (`AgentEditorPage.tsx`) | None | Save, disabled with a reason tooltip; opens a chat after | — | Blank form |
| Rakazo | One panel, Advanced collapsed (`bot-panel.tsx`) | The bot's thread | Save on blur, Save button | — | Name, title, description |
| GoClaw | 7 tabs, sticky save bar | — | Save | Agent, Files, Permissions, Shares… | Presets, then AI summoning |
| OpenClaw | 7 panels | — | Config draft, Save | Overview, Files, Tools, Skills, Channels, Cron, Memory | Custodian chat |

Field coverage (✓ has it):

| Field | Refs that have it | Decision |
| --- | --- | --- |
| Title (role line) | LobeHub, Rakazo, Hermes, GoClaw | add |
| Greeting | LobeHub, Dify, Coze, FastGPT | add |
| Reasoning effort per assistant | LobeHub, Rakazo, Hermes, GoClaw, OpenClaw, FastGPT | add |
| Task prompt ("Reminders") | Onyx | remove |
| Knowledge cutoff | Onyx | remove |
| Context token limit per assistant | GoClaw | remove |
| Output token limit per assistant | LobeHub (chat input), GoClaw, Dify, FastGPT | remove |
| Replace the base system prompt | Onyx ("Not Recommended"), GoClaw `prompt_mode` | remove |

## Decisions

### Fields

1. **Title.** An optional role line, at most 80 characters, shown under the name on cards, in the assistant sidebar,
   in the test chat and on an empty conversation (LobeHub `title`, Rakazo `title`).
2. **Greeting.** Optional, at most 500 characters. It replaces the heading of an empty conversation, above the
   starters; empty means the description, as today (LobeHub `openingMessage`, Dify opener, FastGPT welcome text).
3. **Reasoning effort.** Optional: empty means "Theo hệ thống", or OFF, LOW, MEDIUM, HIGH. The control shows only when
   the chosen model reports `capabilities.reasoning`, as Rakazo shows thinking only for models that support it. A turn
   resolves the conversation's pinned effort, then the assistant's, either outranking the model configuration; then,
   as today (Onyx's order), a level the model configuration names, then the member default.
4. **Removed:** task prompt, knowledge cutoff, both token limits and "replace the base system prompt". Production has
   one assistant and uses none of them; staging has four task prompts on test assistants. The migration appends a
   non-empty task prompt to the instructions after a blank line, then drops the five columns. The system-wide limits in
   `ChatExecutionProperties` stay. Reasons: 1–2 references each, each covered by something else, and replacing the
   base prompt drops citations and safety rules.
5. **New assistants start with search documents and web search on**, code and images off (Onyx starts with all off,
   Rakazo with all on).
6. **Documents are one control.** A "Tìm trong tài liệu nội bộ" switch heads the section and, when on, one picker
   adds sources, document sets and files, each row tagged with its kind. With the switch off the section is disabled
   instead of silently ignored (Onyx `AgentKnowledgePane` and its "Use Knowledge" switch). Storage is unchanged.
7. **"Chỉ trả lời có căn cứ"** stays as one switch row under the documents list, with no fold (owner, 2026-10-16).
8. **Labels move to the Chia sẻ tab**, where they serve discovery (Onyx puts labels with Share and Feature).

### Editor

9. **Three tabs, Cấu hình · Kênh · Chia sẻ** (LobeHub `AgentProfileTabs`), as shadcn `Tabs` with the line variant.
   The URL keeps the tab (`?tab=`).
10. **Cấu hình: config left, test chat right** (Dify, Coze, FastGPT), in a `ResizablePanelGroup`. There is no sticky
    section nav: no reference product has one (Onyx, Dify, FastGPT, LobeHub), and beside the test chat it would squeeze
    the form. The owner dropped it on 2026-10-16. On phones the form is one column and a floating "Chat thử" button opens the test chat in a `Drawer`, as Dify does.
11. **Section contents, top to bottom:**
    - Danh tính: avatar, name, title, description.
    - Hướng dẫn: instructions with their counter.
    - Mở đầu: greeting, starters (at most 8).
    - Tài liệu: the switch, the unified picker, the "Chỉ trả lời có căn cứ" switch.
    - Công cụ: web search, code, images, MCP servers. "Ứng dụng MCP" is left out entirely when the organization has
      no MCP server, rather than showing an empty state. Each person's connection state for an MCP server waits on the
      access discussion (Q12) and is not in this design.
    - Model và suy luận: model and reasoning effort, visible (LobeHub puts both at the top of the profile). The
      title names reasoning beside "Model", which the Vietnamese catalog otherwise renders "Mô hình".
12. **Save stays on the page.** The header shows "Đã lưu" or "Có thay đổi chưa lưu"; Save is disabled with a tooltip
    that says why ("Cần nhập tên", "Chưa có thay đổi"), as Onyx does. Leaving with changes asks, as today. An uploaded
    image applies only on Save, so Cancel undoes it; labels live in the Chia sẻ tab and save on change there. Draft
    and publish for shared assistants is MEM-279; until then Save applies at once, as today.
12a. **One header.** The breadcrumb (Trợ lý › name), the state, Hủy and Lưu sit in the shell header through the
    `breadcrumb` and `actions` of `AppShellHeader`, as the meeting page does; the editor has no second bar under it.
    The owner approved this on 2026-10-16 after the two stacked bars showed the name twice.
13. **The test chat runs the unsaved form.** Each test message carries the form's configuration; the server checks it
    the same way Save does and runs the turn in a temporary conversation of that assistant, without saving the
    configuration (FastGPT's chat test sends the unsaved app; Dify's debug runs the draft). The panel shows the
    greeting and starters as people will see them, marks "đang dùng bản chưa lưu" while the form differs from the
    saved one, and has "Làm mới" to start again. Only people who can edit the assistant can test it. Temporary
    conversations are already hidden from lists and purged after 24 hours.
14. **Create is a short dialog**: name, title and description, with no explanatory line under the title (Rakazo
    `CreateBotForm`). Create saves the assistant
    with the defaults of decision 5 and opens its editor. `/agents/create` redirects to the agents page with the dialog
    open. Templates come through Fork (step 3); "Điền giúp" in this dialog is MEM-276.
15. **Chia sẻ tab**: today's share dialog body (who can use it, people and groups with Chỉ dùng / Được sửa, ownership
    transfer), labels, and for admins "Hiện ở Khám phá" and "Nổi bật". Each change saves at once. The note that people
    only get answers from documents they can read is one muted line under "Quyền truy cập chung", not an alert box.

### Channels

16. **A bot answers with one assistant.** `channel_connections.persona_id`, nullable, `ON DELETE SET NULL`: a bot
    with none, or whose assistant was deleted, answers with the built-in assistant. `ChannelTurns` creates the
    conversation with the bound assistant. The sender's access to that assistant's documents is still checked per turn,
    as Onyx does for Slack. An assistant has many bots; a bot has one assistant (LobeHub `agentBotProvider`, GoClaw
    `ChannelInstance.agent_id`, OpenClaw `bindings`).
17. **The Kênh tab** lists the bots bound to the assistant, with "Thêm kênh" and "Gỡ". "Thêm kênh" lists the
    organisation's bots; choosing one bound elsewhere asks "Chuyển bot sang trợ lý này?" and moves it in one step
    (LobeHub only reports a conflict). Binding needs `CHANNELS_MANAGE` and edit rights on the assistant; others see the
    list read-only. Bots are still created on the admin "Kênh chat" page, which shows each bot's assistant.
18. **Out of this design:** direct messages joining the assistant's web topics, and per-group routing. They follow
    once binding is live.

## API

- `PersonaInput` and `PersonaView` gain `title`, `greeting` and `reasoningEffort`, and lose `taskPrompt`,
  `knowledgeCutoff`, `contextTokenLimit`, `outputTokenLimit` and `replaceBaseSystemPrompt`.
- The send command of a temporary conversation accepts `draftPersona` (the editable fields of `PersonaInput`), allowed
  only to an editor of the conversation's assistant; the turn uses it instead of the stored configuration and skips
  the revision check.
- `PUT /api/channels/{id}/persona` binds or unbinds a bot; `ChannelConnection` responses carry the assistant's id and
  name; `GET /api/chat/personas/{id}/channels` lists the bots of an assistant.
- Migration V181: `persona.title`, `persona.greeting`, `persona.reasoning_effort`; task prompt folded into
  instructions; the five columns dropped. Migration V182: `channel_connections.persona_id`.

## Components

Shadcn primitives come from `web/src/components/ui`; the missing ones (resizable, drawer, kbd…) are added by
[#703](https://github.com/kl3inIT/MemoryOS/pull/703).

| Piece | Build from | Learned from |
| --- | --- | --- |
| Editor shell, tabs | `Tabs` (line variant), `ResizablePanelGroup`, `ScrollArea` | LobeHub `AgentProfileTabs`, Dify `configuration-view` |
| Save bar | existing header, `Tooltip` on the disabled button, `ConfirmDialog` blocker | Onyx disabled-reason tooltip, GoClaw sticky save bar |
| Avatar, name, title, description | `AgentAvatarStudio`, `TextField`, `TextareaField` | Rakazo identity block |
| Greeting, starters | `TextareaField`, `StarterPromptsField` (duplicate check added) | LobeHub `AgentOpening` (duplicate detection) |
| Unified document picker | extend `AgentSourcePicker` with grouped `Command` groups; `ChatFilePickerContent` for files | Onyx `AgentKnowledgePane` |
| Tools and MCP | `SettingRow` + `Switch` | Onyx Actions section |
| Model and effort | `ModelSelector` with `efforts` and `ModelSelectorEffort` | Rakazo thinking select, LobeHub `ReasoningEffortSelect` |
| Test chat | `ChatThread` inside its own `AssistantRuntimeProvider` with a single-thread transport (temporary, `draftPersona`); `Drawer` on phones | FastGPT `ChatTest`, Dify `Debug` |
| Create dialog | `FormDialog` | Rakazo `CreateBotForm` |
| Chia sẻ tab | the body of `AgentShareDialog` (`PrincipalPicker`, `AccessRow`, `GeneralAccess`, `RoleSelect`), `AgentLabelPicker` | Onyx share section |
| Kênh tab | `AppRow`, `ChannelMark`, `ConfirmDialog` for the move | LobeHub `AgentChannel` |
| Empty conversation greeting | `ChatThread` empty state, `ChatStarterPrompts` | — |

`AgentPreview` is deleted in step 2; until the test chat arrives in step 3 the form is one column with no empty
panel beside it.

## Not in scope

- Connection state per person and the silent MCP skip (Q12, to be discussed).
- "Điền giúp" (MEM-276), Agent Builder (MEM-280), draft and publish (MEM-279), memory per assistant (MEM-278),
  organisation backup models (MEM-277).
- Direct messages joining web topics; follow-up suggestions; variables in instructions.

## Risks

- **A test turn with an unsaved configuration could reach documents the editor cannot read.** The draft is validated
  exactly as Save validates it, and retrieval still runs with the editor's own access.
- **Removing fields changes answers.** No production assistant uses them; the task prompt is kept inside the
  instructions.
- **Moving a bot changes who answers a channel.** The move asks first and names both assistants.
