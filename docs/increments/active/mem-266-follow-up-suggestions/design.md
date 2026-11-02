# MEM-266: Follow-up suggestions after an answer

## Why

Chat offers suggestions only on an empty conversation (an assistant's conversation starters). After an answer the
member has to think of the next question alone. Onyx and Glean show a few follow-up questions under the answer, and
assistant-ui ships the mechanism (`adapters.suggestion` on `useAISDKRuntime`, `ThreadPrimitive.Suggestions`,
`SuggestionPrimitive.Trigger`, the Follow-up suggestions element).

Each set of suggestions is one more model call on the Tenant's shared, paid key, so the feature is an administrator's
choice and starts off.

## References

| Product | What it does |
| --- | --- |
| assistant-ui 0.15.32 (`@assistant-ui/core` 0.3.32) | `useAISDKRuntime` calls `adapters.suggestion.generate({ messages, signal })` once a run settles with an assistant message last, clears the suggestions when the next run starts, and exposes them as `thread.suggestions`; `SuggestionPrimitive.Trigger send` appends the prompt as the next message. `createSuggestionAdapter({ complete })` builds a transcript prompt in the browser and hands it to `complete` |
| Onyx, Glean | A few short follow-up questions under the latest answer; clicking one asks it |

## Decisions

| Topic | Decision | Why |
| --- | --- | --- |
| Where suggestions are written | The API, through `POST /api/chat/sessions/{sessionId}/messages/{messageId}/follow-ups`; the browser sends no prompt and never calls a provider | The provider key and the prompt stay on the server; the server reads the transcript it trusts |
| Web adapter | A `SuggestionAdapter` of our own (`generate` calls the endpoint with the last assistant message's id) instead of `createSuggestionAdapter` | `createSuggestionAdapter` builds the prompt from the browser's transcript and passes it to `complete`; sending that prompt to the server would let a browser choose what the model is asked. Deviation from the issue's suggestion, same runtime contract |
| Model | The `CHAT_NAMING` task model, resolved as naming resolves it (`ChatModelSelector.resolveFlow`) | Same small, cheap task as a title; no new model setting (decided with the owner) |
| Usage | Recorded as its own `AiUsageFlow.CHAT_FOLLOW_UPS`, failed calls included, labelled "Gợi ý câu hỏi tiếp theo" in the PDF report and "Follow-up suggestions" in AI costs | The cost is visible apart from Chat and naming. V198 widens the `ai_usage` flow check |
| Administrator control | `chat_settings.follow_up_suggestions`, default `FALSE`; a switch in Quản trị › Chat › Câu trả lời, saved by `PUT /api/chat/settings/follow-ups` with the settings revision | One Tenant switch, as Deep research and grounded answers. No per-member preference for now (owner's decision) |
| When the server answers with nothing | Setting off; the message is not the latest of the selected branch; not an assistant message; not `COMPLETED`; a refusal (`refusal_reason`); a temporary conversation (Agent Builder and the assistant test chat); a reply still running; no free turn slot; the asker's AI budget spent; the model failing or answering nothing usable. All return `200 {"suggestions": []}` | Suggestions are optional; the page renders nothing rather than an error |
| When the server refuses | A conversation the caller does not own, or an unknown message, is `404 CHAT_UNAVAILABLE`, as every owner-only Chat route | No disclosure of other people's conversations |
| Prompt | The last six messages of the branch ending at the answer, each cut to 2,000 characters, as untrusted data; the instruction asks for exactly three short questions the user would ask next, one per line, in the conversation's language. Reasoning off unless the task model reasons; 256 output tokens (4,096 for a reasoning model); a 15 s deadline (60 s reasoning) | Same bounds and data handling as naming |
| Parsing | Lines are stripped of bullets, numbering and quotes, blanks dropped, duplicates removed ignoring case, each cut to 160 characters, at most three kept | Models format lists in many ways |
| Persistence | None. Suggestions are not stored; a reloaded conversation shows none | Matches assistant-ui's runtime behaviour; storing them would need a schema for little value |
| Logging | `chat.follow_ups.skipped` (debug, with `reason`), `chat.follow_ups.failed` and `chat.follow_ups.usage_not_recorded` (warn, with `error_type`); never the conversation or the suggestions | [Observability](../../../guidelines/observability.md) |

## Screens (Figma, page "draft", section 4:25368)

| Frame | Node | Implemented as |
| --- | --- | --- |
| 01 desktop | 4:25369 | Under the last assistant answer, below its action bar (16 px: the frame's 12 px gap plus 4 px padding), the suggestions wrap in a row with 8 px gaps. Each chip is `Button prominence="secondary"` with `h-auto max-w-full text-left whitespace-normal`, as `StarterPromptButtons`. No label |
| 02 loading | 4:25393 | Three `Skeleton` pills of the chip height while the request runs |
| 03 phone 390 | 4:25417 | Below the thread's `@xl` container width the chips stack at full width |
| 04 admin | 4:25441 | `SettingRow` with a `Switch` after "Chỉ trả lời từ tài liệu của tổ chức", icon `Sparkles`, title "Gợi ý câu hỏi tiếp theo", the frame's description. The frame's "Mới · MEM-266" badge is a design annotation and is not shipped |

Clicking a chip sends the question at once (`SuggestionPrimitive.Trigger send`), unlike a starter, which fills the
composer. A new question clears the suggestions (assistant-ui clears them when a run starts).

Not shown: a shared read-only conversation (`useExternalStoreRuntime`, no adapter), the assistant test chat and the
Agent Builder (their own runtime without an adapter, and temporary conversations on the server), a temporary
conversation in Chat, a failed or stopped answer, and any answer but the last.

## Contracts

- `POST /api/chat/sessions/{sessionId}/messages/{messageId}/follow-ups` (`suggestChatFollowUps`) → `ChatFollowUpsResponse { suggestions: string[] }`, at most three.
- `ChatSettingsResponse.followUpSuggestions: boolean`; `PUT /api/chat/settings/follow-ups` (`saveChatFollowUps`) with `ChatFollowUpsRequest { followUpSuggestions, revision }`, model managers only, audited as `CHAT_SETTINGS_CHANGE` with `followUpSuggestions`.
- V198: `chat_settings.follow_up_suggestions BOOLEAN NOT NULL DEFAULT FALSE`; the `ai_usage` flow check gains `CHAT_FOLLOW_UPS`.
- `ChatTurnService.suggestFollowUps(actor, session, message)`; `ChatModelExecutor.suggestFollowUps(binding, messages, accounting)`.

## Out of scope

- A per-member switch, persisting suggestions, suggestions in chat channels (Lark, Zalo, WhatsApp).
- Measuring the real token cost per answer; that is a staging check in the plan.
