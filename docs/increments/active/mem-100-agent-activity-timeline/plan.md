# MEM-100 plan

Design: [design.md](design.md). Branch: `mem-100-agent-activity-timeline`.

## 0. Spikes (decide before building)

- [ ] Prove with a `chat-transport.test.ts` case that repeated `tool-input-available` for one `toolCallId` keeps the `dynamic-tool` part running in `@assistant-ui/ai-sdk` 0.0.4. If not, carry progress as a `data-*` part keyed by tool call ID and update design.md.
- [ ] Route one reasoning model through `OpenAiResponsesChatModel` without native Web search: tool loop, Stop, length finish and usage accounting behave as on Chat Completions.
- [ ] Confirm the staging model returns `response.reasoning_summary_text.delta` with `reasoning.summary: "auto"`; record the result.
- [ ] Decide how Responses capability is identified (explicit model option or endpoint rule) and record it in design.md.

## 1. Backend contract

- [ ] Rename `ChatSearchEvent` to `ChatToolEvent`; add `toolName` and terminal `durationMs` with validation; add `ChatReasoningDelta`; introduce sealed `ChatActivityEvent`.
- [ ] Switch `SearchTool`, `WebTools`, `ChatEvidence`, `ChatModelExecutor` and `ChatTurnService` to `Consumer<ChatActivityEvent>`.
- [ ] Add the `ChatToolActivity` inspector; register it runner-wide; remove SearchTool as inspector; use the real tool call ID in WebTools.
- [ ] Generalize `NativeWebSearch` into `ChatModelTurns` with the activity consumer.
- [ ] `StreamBufferWriter`: `tool` and `reasoning` event types with byte accounting; `ChatEventStream` and `ChatStreamController` (`oneOf`, description) emit `tool` and `reasoning`.
- [ ] `OpenAiChatProviderAdapter`: select Responses for native Web search or reasoning models per the spike; `OpenAiResponsesChatModel` requests summaries and forwards deltas.
- [ ] `ChatObservationSanitizer` and summary allowlists: no raw arguments, results or provider errors.

## 2. Persistence and history

- [ ] V54 `chat_message.activity` with CHECK constraints (ASSISTANT-only, step count, reasoning length, byte cap).
- [ ] Collect steps, summaries, `textOffset` and bounded reasoning in `ChatTurnService.Active`; seal in `finish`.
- [ ] Write `activity` in `JdbcChatRepository.finish` with `sources`/`artifacts`; map it in `ChatMessage` and `ChatMessageResponse`.
- [ ] `openapi.yml`: `ToolEvent`, `ReasoningEvent`, `ChatMessage.activity`; regenerate the hey-api client.

## 3. Web

- [ ] Transport: handle `tool` and `reasoning`; ignore unknown event types; defer `text-start`; map stages per design; remove `searchProgress`.
- [ ] `toUiMessages`: reasoning and `dynamic-tool` parts from `activity` in `textOffset` order.
- [ ] Add reasoning, tool-group and tool-fallback element sources with adaptation headers; restyle via `surfaces.tsx`; localized group label.
- [ ] `AssistantMessage`: `GroupedParts` with activity, reasoning and tool groups; quote-selectable answer text; auto-collapse on first text.
- [ ] Tool UIs for `searchKnowledge`, `web_search`, `open_url`, `read_file`, `render_gui`; fallback for other names; delete `ChatSearchStatus` after moving its query/document block.
- [ ] vi/en strings, keyboard and focus on triggers, reduced motion, mobile width.

## 4. Tests

- [ ] `StreamBufferWriterTest`: `tool`/`reasoning` byte accounting, trim and gap reset.
- [ ] `ChatEventStreamTest`: tool stages and reasoning replay in sequence before text and terminal, with stable wire identity.
- [ ] `SearchToolTest`, `WebToolsTest`: real tool call IDs, `toolName`, durations; no events attributed to other tools.
- [ ] `OpenAiChatProviderAdapterTest`: Responses selection rule. `OpenAiResponsesChatModelTest`: summary deltas forwarded; `reasoning.summary` sent only when enabled; native Web search unchanged.
- [ ] `ChatPersistenceIntegrationTest`: activity commits with the terminal winner; canceled turn keeps steps; legacy rows read empty; constraints reject oversize data.
- [ ] `ChatSessionApiIntegrationTest`: native search and presentation tools persist activity through authorized history; `OpenApiContractTest` updated.
- [ ] `chat-transport.test.ts`: chunk mapping, dedup by sequence, unknown events ignored, deferred `text-start`, history conversion and interleaving.
- [ ] Renderer Vitest: grouping order, collapsed label, failed step, fallback tool, no empty reasoning block.
- [ ] Playwright: update `chat.spec.ts` ("shows effective search queries…", "shows one waiting indicator…", "keeps sources through … and reload"); add timeline-after-reload assertions.

## 5. Documentation

- [ ] `docs/specs/chat.md`: Web search progress (L27), transport (L79), SSE endpoint row (L141), executor and tool loop (L163), streaming buffer (L173, L179), `searchKnowledge` registration (L190), sources and activity commit (L208), SSE events (L210), tool observations (L212).
- [ ] `docs/specs/chat-models.md`: Responses selection and the reasoning capability.
- [ ] `docs/tests/chat.md`: transport rows (L56, L63), waiting indicator (L60), `ChatEventStreamTest` (L94), query/reading progress (L168), native search integration (L180), commit and replay order (L184–185).

## 6. Verification

- [ ] `./gradlew clean check` and `pnpm check`.
- [ ] Before/after screenshots with realistic fixtures: desktop and mobile, light and dark; running, collapsed, expanded, failed step, reloaded history.
- [ ] Staging: a live turn with internal search, Web search and a file read on a reasoning model and a non-reasoning model; reload mid-run and after completion; record reasoning availability.
