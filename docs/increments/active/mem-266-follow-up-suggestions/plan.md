# Plan — MEM-266 Follow-up suggestions

Design: [design.md](design.md).

| Step | Change | Verification |
| --- | --- | --- |
| 1. Storage and setting | V199 (`chat_settings.follow_up_suggestions`, `ai_usage` flow check with `CHAT_FOLLOW_UPS`); `ChatSettingsEntity`, `ChatSettingsService.saveFollowUps`, `View.followUpSuggestions`, `followUpSuggestions(actor)` read for the turn service | API round trip: default off, saved on, stale revision refused |
| 2. Generation | `ChatTurnPersistence.followUpInput` (owner check, latest completed answer of the branch, not temporary, not refused, no running reply, last six messages); `ChatModelExecutor.suggestFollowUps`; `ChatTurnService.suggestFollowUps` (setting, slot, budget, `CHAT_NAMING` model, usage as `CHAT_FOLLOW_UPS`); `FollowUpSuggestions.parse` | Core unit tests for every skip case and usage recording; parser unit test |
| 3. API | `ChatSessionEditorController.suggestFollowUps`, `ChatFollowUpsResponse`; `ChatSettingsController.saveFollowUps`, `ChatFollowUpsRequest`; `openapi.yml` (minimal insert) and the Hey API client | `ChatSessionApiIntegrationTest` cases; `OpenApiContractTest` |
| 4. Usage labels | `UsageReportPdf` and the AI costs page label the new flow | Type check (the label map is typed by the flow union) |
| 5. Web | `createChatFollowUpAdapter` passed to `useAISDKRuntime` in `chat-runtime-provider.tsx`; `ChatFollowUps` under the last answer (chips, skeleton); admin switch | Vitest for the adapter and the rendering; one Playwright spec with mocked routes |
| 6. Documents | `docs/specs/chat.md`, `docs/specs/ai-usage.md`, `docs/tests/chat.md`, roadmap Active row | Reviewed with the change |

## Acceptance

- [ ] With the switch off (default) no request is made and nothing is shown.
- [ ] With it on, three suggestions appear under the last answer after it completes; clicking one asks it.
- [ ] A failed or stopped answer, a shared conversation, the assistant test chat and the Agent Builder show none.
- [ ] Usage shows "Gợi ý câu hỏi tiếp theo" apart from Chat; the real token cost per answer is measured on staging.
- [ ] No conversation or suggestion text appears in logs.
