# OpenAI Responses model, structured after Spring AI

Status: **proposed 2026-09-30; not started.** No Linear issue yet. Related:
[Chat Web search](../chat-web-search/design.md), which introduced the model.

## Problem

`ai.openai.OpenAiResponsesChatModel` (370 lines) is MemoryOS's `ChatModel` over `/v1/responses`, used for native
Web search and encrypted reasoning echo. One class holds request building (`request`), message-to-input mapping
(`input`), stream handling (an inner class with `start`, `reason`, `done`, `search`, `cite`, `finish`), citation
extraction and failure handling. It is hard to test one concern without the others, and failures surface as
untyped exceptions.

## Owner decision (2026-09-30)

Restructure it now after Spring AI's implementation, without upgrading. When MemoryOS can move to Spring AI 2.1
(see the roadmap's other tracked work), this model is replaced by Spring AI's and deleted.

## Reference

Spring AI `v2.1.0-M1`, `models/spring-ai-openai/src/main/java/org/springframework/ai/openai/responses/`
([#7006](https://github.com/spring-projects/spring-ai/pull/7006)):

| Spring AI class | Role |
| --- | --- |
| `OpenAiResponsesChatModel` | `call`/`stream`, retries, Micrometer observation through `ChatModelObservationConvention`; delegates everything else |
| `ResponsesRequestBuilder` | `Prompt` and options to `ResponseCreateParams` |
| `ResponsesItemMapper` | Messages to input items and output items back to messages, one part per item and in order |
| `ResponsesStreamAssembler` | Stream events to `ChatResponse` chunks, stamped with output index and response id |
| `OpenAiResponsesMetadata` | Response id, status, incomplete reason, usage |
| `OpenAiResponsesException` | Typed failure for a failed or incomplete response |
| `HostedTool` | Server-side tools (web search and others) as typed options |

Rules taken from it: only `function_call` items become tool calls, so the tool loop never looks for a local
`web_search`; reasoning is replayed only when it came from this provider; a completing item is emitted without the
text already streamed as deltas; every chunk carries the response id.

| | |
| --- | --- |
| Proposed difference | Spring AI 2.0.1 has no `MessagePart`; MemoryOS keeps carrying encrypted reasoning and citations in message metadata as today, so the mapper maps to metadata instead of parts |
| Benefit | Each concern is unit-tested on recorded SSE fixtures; the later swap to Spring AI is a class-for-class replacement |
| Simpler baseline | Leave the class as is until the upgrade. Rejected by the owner decision |

## Design

In `ai.openai`, package-private:

- `OpenAiResponsesChatModel` keeps its public factory and `ChatModel` contract, and only orchestrates.
- `ResponsesRequestBuilder`: tools, hosted `web_search` and `tool_choice`, `store=false`, reasoning include.
- `ResponsesInputMapper`: `Message` to input items, including the encrypted reasoning echo.
- `ResponsesStreamAssembler`: stream events to `ChatResponse` chunks, Web progress and `url_citation` sources.
- `ResponsesMetadata` record and `OpenAiResponsesException` (a typed `AiException` subtype with a stable code).
- Observation through the existing model call observation; no second metric.

Behavior, the Chat Web contract and every existing SSE fixture test are unchanged.

## Verification

Existing fixture tests (hosted search, function-call continuation, required, failure, final cycle, non-Web
delegation) pass unchanged; new unit tests per class; `clean check`.
