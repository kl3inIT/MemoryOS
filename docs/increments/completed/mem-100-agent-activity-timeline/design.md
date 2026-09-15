# MEM-100 — Agent activity timeline

Tracking: [MEM-100](https://linear.app/memory-os/issue/MEM-100). Follow-up that depends on it: [MEM-101 Deep research](https://linear.app/memory-os/issue/MEM-101).

## Outcome

Every Chat answer shows what the agent did before and while answering: model reasoning when the provider exposes it, and one step per tool call (`searchKnowledge`, `web_search`, `open_url`, `read_file`, `render_gui`) with a bounded summary, status and duration. The timeline streams live, collapses to a one-line summary once the answer text starts, and is restored from history after the turn ends, including canceled and failed turns.

## Current implementation (main `a377178`)

- `ChatModelExecutor.execute` registers tools with `Tool.fromInstance` and hands the tool loop to Embabel. `SearchTool` is also registered through `runner.withToolCallInspectors(searchTool)`. That registration is runner-wide and `SearchTool.beforeToolCall`/`afterToolCall` do not filter by tool name, so SearchTool currently emits `STARTED`/`COMPLETED` under whatever tool call is running.
- `WebTools.run` invents `"web-" + kind + "-" + UUID` identifiers instead of the provider tool call ID; `ChatSearchStatus` depends on that prefix.
- `ChatSearchEvent(toolCallId, stage, source, search, documents)` is the only progress event, consumed as `Consumer<ChatSearchEvent>` by SearchTool, WebTools, ChatEvidence and `NativeWebSearch.Turn`. `StreamBufferWriter` buffers `text-delta`, `search` and `outcome`; `ChatEventStream` maps them to SSE.
- Progress is not persisted. After buffer loss or on history load only `sources` and `artifacts` return (`JdbcChatRepository.finish`).
- `OpenAiChatProviderAdapter` is the only `ChatProviderAdapter`. It wraps Spring AI `OpenAiChatModel` (Chat Completions) and switches to `OpenAiResponsesChatModel` only when the model option `webSearch` is `native` (`OpenAiChatProviderAdapter.java` L89-90). Responses sends `reasoning.effort` and includes `reasoning.encrypted_content`, which cannot be displayed; `StreamState` ignores reasoning summary events. Embabel `generateStream()` returns text only.
- The web transport emits `text-start` eagerly, folds progress into `metadata.custom.searchProgress` (last 16 entries), and throws on unknown event types. `AssistantMessage` renders `MessagePrimitive.Parts` plus `ChatSearchStatus`.
- No assistant-ui reasoning, tool-group or tool-fallback element is installed. Existing elements (for example `web-search.tsx`) are copied registry sources with an adaptation header.

## Reference

- Onyx `web/src/app/app/message/messageComponents/timeline/`: collapsed header with duration, expandable steps, per-tool renderers; tool calls and reasoning are persisted for replay.
- assistant-ui [Chain of Thought guide](https://www.assistant-ui.com/docs/guides/chain-of-thought): `MessagePrimitive.GroupedParts` with `groupPartByType` (the `ChainOfThoughtPrimitive` path is legacy), plus the `Reasoning`, `Tool group` and `Tool fallback` elements.
- Mobbin, recorded in MEM-100: Dropbox Dash (inline "Thinking…" with the current search line), Customer.io (collapsed "Thought for 2 seconds" and "Searched docs" rows), Lindy and Plane (checklist when expanded). The timeline stays inline because the right-hand panel belongs to evidence.

## Decisions

### One activity contract for every tool

The progress contract becomes tool-neutral so every current tool, and the research agents of MEM-101, share it:

- `ChatSearchEvent` is renamed `ChatToolEvent` and gains `toolName` (bounded, allowlisted to registered tool names) and `durationMs` (terminal stages only). `STARTED`, `COMPLETED` and `FAILED` apply to every tool; `SEARCHING`, `SELECTING`, `EXPANDING` and `SOURCE` remain search and Web stages.
- `ChatReasoningDelta(text)` is added.
- Both implement a sealed `ChatActivityEvent`. Every producer (tools, evidence, provider models) accepts `Consumer<ChatActivityEvent>`.
- The SSE event `search` becomes `tool`, the OpenAPI schema `SearchEvent` becomes `ToolEvent`, and a new SSE event and schema `reasoning` carries `{assistantMessageId, sequence, text}`. All share the run sequence, byte accounting, replay and TTL.
- No external client consumes the stream, so the rename is made once, together with the other contract changes in this increment, instead of a second break in MEM-101.

The web transport ignores unknown event types instead of throwing, so a tab running older JavaScript during a deployment falls back to history rather than failing the turn.

### One generic inspector

A single `ChatToolActivity` `ToolCallInspector` is registered on the runner. It emits `STARTED` with the real tool call ID and `COMPLETED`/`FAILED` with `AfterToolCallContext.getDurationMs()`. SearchTool stops being an inspector and reads the current tool call ID from that component; WebTools uses the same ID. This fixes the misattributed search events.

### Provider-neutral reasoning, OpenAI first

Reasoning is provider-neutral in everything above the adapter: the event, persistence, transport and UI never name a provider. Adding a provider later only means that its model emits `ChatReasoningDelta` into the per-turn consumer.

- `NativeWebSearch` is generalized into the per-turn model view `ChatModelTurns` (`ChatModel forTurn(Turn)`, `Turn(evidence, Consumer<ChatActivityEvent> events, boolean webRequired, Runnable checkActive)`). It is the existing extension point, widened rather than a new single-implementation interface.
- Emitting reasoning is gated by the provider-neutral capability `ModelSettings.Capabilities.reasoning`.
- Only the OpenAI adapter emits reasoning in this increment. `OpenAiChatProviderAdapter` selects `OpenAiResponsesChatModel` when native Web search is configured **or** the model has `reasoning=true` on the Responses-capable OpenAI API, and keeps Chat Completions for other OpenAI-compatible endpoints. How Responses capability is identified (explicit model option or endpoint) is decided in the spike and recorded here; it must not rely on silent URL guessing.
- Responses requests add `reasoning.summary: "auto"` when reasoning is enabled; `StreamState` handles `ResponseReasoningSummaryTextDeltaEvent` (present in `openai-java-core` 4.49.0).
- Gemini, Anthropic and other adapters do not exist yet and receive no reasoning work (ADR 0002: no speculative surfaces). Models that emit no reasoning render no reasoning block.

### Persistence

V59 adds `chat_message.activity jsonb NOT NULL` (default `{"steps": [], "reasoning": []}`) with a `NOT VALID` CHECK validated by V60, ASSISTANT-only, requiring both keys, in the style of V34/V42:

- `steps`: at most 32 entries of `{toolCallId, toolName, status, startedAt, durationMs, textOffset, summary}`.
- `reasoning`: at most 16,000 characters plus a truncation marker, with `textOffset`.
- A 131,072-byte cap on the column.

`summary` is a per-tool allowlist, never raw arguments or results: `searchKnowledge` keeps up to eight query texts, effective filters and up to ten reading documents already authorized at answer time; `web_search` keeps queries and result hostnames; `open_url` keeps hostnames; `read_file` keeps the file ID and display name; `render_gui` keeps the artifact ID and title. Unknown tools keep name, status and duration only. `textOffset` is the answer length when the item started, so history can interleave activity with text. The column carries no provider field.

`ChatTurnService.Active` collects activity beside sources. `JdbcChatRepository.finish` writes it in the same statement as `sources` and `artifacts`, so the terminal winner commits activity atomically, including partial canceled or failed answers. History returns `activity`; legacy rows return the empty object. Like `sources`, activity is historical presentation data and grants no access.

### Transport mapping

`@assistant-ui/ai-sdk` 0.0.4 marks a tool call complete once `output-available` arrives and ignores `preliminary`, so intermediate progress travels in the tool input:

| Server event | UI message chunk |
| --- | --- |
| `tool` `STARTED` | `tool-input-start`, then `tool-input-available` with `{stage}` |
| `tool` `SEARCHING`/`SELECTING`/`EXPANDING` | `tool-input-available` with the updated summary |
| `tool` `COMPLETED` | `tool-output-available` with the final summary and `durationMs` |
| `tool` `FAILED` | `tool-output-error` with a localized, non-sensitive message |
| `tool` `SOURCE` | unchanged behavior: merged into `metadata.custom.sources` |
| `reasoning` | `reasoning-start` once, `reasoning-delta`, `reasoning-end` when text or a tool starts |

Tool parts are `dynamic-tool` parts because the tools run on the server. `text-start` is emitted before the first `text-delta`, not at stream start, so the activity group precedes the answer. `searchProgress` metadata and `ChatSearchStatus` are removed. `toUiMessages` builds reasoning and tool parts from `activity` in `textOffset` order, splitting saved text where a later item started. Mid-run reload stays within the existing replay limits (cursor, 10-minute TTL, 4 MiB per run); after the terminal outcome, history is the source.

### Rendering

`AssistantMessage` replaces `MessagePrimitive.Parts` with `MessagePrimitive.GroupedParts` and `groupPartByType({ reasoning: ["group-activity", "group-reasoning"], "tool-call": ["group-activity", "group-tool"] })`. The text case keeps `AnswerMarkdown` inside the `data-aui-quote-selectable` wrapper so quoting stays answer-only.

- Install `reasoning`, `tool-group` and `tool-fallback` element sources the same way `web-search.tsx` was added, with an adaptation header, restyled through `surfaces.tsx`.
- `ToolGroupTrigger` takes a localized label instead of the fixed "N tool calls", built from group counts and elapsed time ("Đã tìm 3 truy vấn · đọc 5 tài liệu · 14s").
- Per-tool UI is registered by tool name with `makeAssistantToolUI`: `searchKnowledge` reuses the query/filter/document block from `ChatSearchStatus`; `web_search` and `open_url` reuse `web-search.tsx`; `read_file` shows the file name; `render_gui` shows the artifact title. Any other tool name renders through the fallback with name, status and duration, so new tools appear without UI changes.
- The group is open while running and closes when the first answer text arrives; the user can reopen it. `useToolCallElapsed` gives live durations and persisted `durationMs` gives them in history.

## Extension points

- New tool: register it; the generic inspector emits its steps, the fallback renders it, and an optional summary allowlist entry and tool UI improve the display.
- New provider reasoning: its model implements `ChatModelTurns` and emits `ChatReasoningDelta` when `capabilities.reasoning` is true.
- MEM-101: research agents reuse `ChatToolEvent`, adding identifiers for nesting when that increment is designed.

## Out of scope

Deep research (MEM-101), parallel tool execution, human approval, re-running or editing steps, reasoning for adapters that do not exist, and exposing raw tool arguments or results.

## Risks

- Routing reasoning models through Responses changes the model path for those models; the tool loop, Stop, length finish and token accounting must be proven before release.
- Reasoning summaries may be unavailable for the configured organization or model; the feature degrades to tools only.
- Interleaving by `textOffset` depends on text between tool calls; most turns produce activity before text.
- The rename touches native Web search, SearchTool, WebTools, ChatEvidence, the controller contract and several integration tests at once.
- Updating a tool input after it first parses as complete JSON makes the assistant-ui `ToolInvocationTracker` log development-only warnings (EDGE_CASES A.2/A.4). They are guarded by `NODE_ENV !== "production"`, stream calls are not re-fired, and the rendered part still reflects the latest input; server-executed tools register no client `streamCall`.
