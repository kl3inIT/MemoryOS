# MEM-100 plan

Design: [design.md](design.md). Branch: `mem-100-agent-activity-timeline`.

## 0. Spikes (decide before building)

- [x] Prove with a `chat-transport.test.ts` case that repeated `tool-input-available` for one `toolCallId` keeps the `dynamic-tool` part running in `@assistant-ui/ai-sdk` 0.0.4. If not, carry progress as a `data-*` part keyed by tool call ID and update design.md.
- [ ] Route one reasoning model through `OpenAiResponsesChatModel` without native Web search: tool loop, Stop, length finish and usage accounting behave as on Chat Completions.
- [ ] Confirm the staging model returns `response.reasoning_summary_text.delta` with `reasoning.summary: "auto"`; record the result.
- [x] Decide how Responses capability is identified (explicit model option or endpoint rule) and record it in design.md.

## 1. Backend contract

- [x] Rename `ChatSearchEvent` to `ChatToolEvent`; add `toolName` and terminal `durationMs` with validation; add `ChatReasoningDelta`; introduce sealed `ChatActivityEvent`.
- [x] Switch `SearchTool`, `WebTools`, `ChatEvidence`, `ChatModelExecutor` and `ChatTurnService` to `Consumer<ChatActivityEvent>`.
- [x] Add the `ChatToolActivity` inspector; register it runner-wide; remove SearchTool as inspector; use the real tool call ID in WebTools.
- [x] Generalize `NativeWebSearch` into `ChatModelTurns` with the activity consumer.
- [x] `StreamBufferWriter`: `tool` and `reasoning` event types with byte accounting; `ChatEventStream` and `ChatStreamController` (`oneOf`, description) emit `tool` and `reasoning`.
- [x] `OpenAiChatProviderAdapter`: select Responses for native Web search or reasoning models per the spike; `OpenAiResponsesChatModel` requests summaries and forwards deltas.
- [x] Summary allowlists: steps record queries, filters, reading documents and citations only; tool arguments, results and provider errors are never recorded or streamed (the existing `ChatObservationSanitizer` is unchanged).

## 2. Persistence and history

- [x] V59 `chat_message.activity` with CHECK constraints (ASSISTANT-only, step count, reasoning length, byte cap).
- [x] Collect steps, summaries, `textOffset` and bounded reasoning in `ChatTurnService.Active`; seal in `finish`.
- [x] Write `activity` in `JdbcChatRepository.finish` with `sources`/`artifacts`; map it in `ChatMessage` and `ChatMessageResponse`.
- [x] `openapi.yml`: `ToolEvent`, `ReasoningEvent`, `ChatMessage.activity`; regenerate the hey-api client.

## 3. Web

- [x] Transport: handle `tool` and `reasoning`; ignore unknown event types; defer `text-start`; map stages per design; remove `searchProgress`.
- [x] `toUiMessages`: reasoning and `dynamic-tool` parts from `activity` in `textOffset` order.
- [x] Adapt the assistant-ui reasoning and tool-group elements into one `activity-group` element with an adaptation header, restyled via `surfaces.tsx`, with a localized group label (see decisions).
- [x] `AssistantMessage`: `GroupedParts` with activity, reasoning and tool groups; quote-selectable answer text; auto-collapse on first text.
- [x] Tool UIs for `searchKnowledge`, `web_search`, `open_url`, `read_file`, `render_gui`; fallback for other names; delete `ChatSearchStatus` after moving its query/document block.
- [x] vi/en strings, keyboard and focus on triggers, reduced motion, mobile width.

## 4. Tests

- [x] `StreamBufferWriterTest`: `tool`/`reasoning` byte accounting, trim and gap reset.
- [x] `ChatEventStreamTest`: tool stages and reasoning replay in sequence before text and terminal, with stable wire identity.
- [x] `SearchToolTest`, `WebToolsTest`: real tool call IDs, `toolName`, durations; no events attributed to other tools.
- [x] `OpenAiChatProviderAdapterTest`: Responses selection rule. `OpenAiResponsesChatModelTest`: summary deltas forwarded; `reasoning.summary` sent only when enabled; native Web search unchanged.
- [x] `ChatPersistenceIntegrationTest`: activity of a canceled turn commits with the terminal winner; user rows read empty and reject activity. Oversize history is bounded before persistence by `ChatActivityRecorderTest`.
- [ ] `ChatSessionApiIntegrationTest`: native search and presentation tools persist activity through authorized history; `OpenApiContractTest` updated.
- [x] `chat-transport.test.ts`: chunk mapping, dedup by sequence, unknown events ignored, deferred `text-start`, history conversion and interleaving.
- [ ] Renderer Vitest: grouping order, collapsed label, failed step, fallback tool, no empty reasoning block.
- [x] Playwright: update `chat.spec.ts` ("shows effective search queries…", "shows one waiting indicator…", "keeps sources through … and reload"); add timeline-after-reload assertions.

## 5. Documentation

- [x] `docs/specs/chat.md`: Web search progress (L27), transport (L79), SSE endpoint row (L141), executor and tool loop (L163), streaming buffer (L173, L179), `searchKnowledge` registration (L190), sources and activity commit (L208), SSE events (L210), tool observations (L212).
- [x] `docs/specs/chat-models.md`: Responses selection and the reasoning capability.
- [x] `docs/tests/chat.md`: transport rows (L56, L63), waiting indicator (L60), `ChatEventStreamTest` (L94), query/reading progress (L168), native search integration (L180), commit and replay order (L184–185).

## 6. Verification

- [ ] `./gradlew clean check` and `pnpm check`.
- [ ] Before/after screenshots with realistic fixtures: desktop and mobile, light and dark; running, collapsed, expanded, failed step, reloaded history. After-screenshots for running, stopped, completed and expanded (light/dark) and mobile running are in `.tmp/mem100-*.png`; before-screenshots and a reload capture are not recorded.
- [ ] Staging: a live turn with internal search, Web search and a file read on a reasoning model and a non-reasoning model; reload mid-run and after completion; record reasoning availability.

## Decisions and evidence (2026-09-15)

- Spike: repeated `tool-input-available` updates one `dynamic-tool` part in place and keeps it `input-available` (AI SDK `readUIMessageStream`).
- Responses capability is an explicit model option: `reasoningSummary: "auto"`, valid only on a reasoning configuration. No endpoint guessing.
- The wire event is renamed `search` → `tool` with schema `ToolEvent`, plus `reasoning`/`ReasoningEvent`.
- The UI uses one adapted `activity-group` element (from assistant-ui tool-group and reasoning, on the kit's radix-ui Collapsible) and a fallback step for unknown tools instead of copying `tool-fallback`, whose approval/argument UI does not apply to server-executed tools.
- Live OpenAI reasoning summaries are unverified: the local key returned `credit_balance_exhausted`. Staging acceptance remains open.
- Local gates: focused core/api tests, `ChatPersistenceIntegrationTest`, `ChatSessionApiIntegrationTest`, `OpenApiContractTest`; web lint, i18n audit, format, typecheck, 236 unit tests; Chromium `chat.spec.ts` progress, waiting and five reload cases (run with one worker).
