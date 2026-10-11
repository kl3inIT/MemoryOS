# Verification — Anthropic provider adapter (MEM-242)

## Automated

- PR #572: `AnthropicProviderAdapterTest` (five wire tests on a local HTTP server and a raw socket), the boundary tests and `model-catalog.test.ts`; CI green on the merged head.
- PR 2: the cancellation test also asserts that Spring AI logs no warning or error when a stream is cancelled before or after its response starts.

## Staging, 2026-10-11

Release `a621fa5d`. The owner created the connection from the *Claude* preset at `https://api.anthropic.com` with their own key and added `claude-haiku-5-5`, `claude-sonnet-5-5` and `claude-opus-5-5` from the model listing. The turns below ran on `claude-haiku-5-5` as the test accounts `rag-benchmark@memoryos.test` and `bm-tasco@memoryos.test`, in temporary conversations, through `POST /api/chat/sessions/{id}/messages` and the events stream.

| Check | Result |
| --- | --- |
| Connection and Models API listing | Three models listed and added; audit `llm_provider.create` with `adapter: anthropic` |
| Reasoning level `LOW` | Thinking streamed as `reasoning` events during the turn; the turn completed |
| Tools with thinking between cycles | `search_knowledge` called twice in one turn (Anthropic tool ids `toolu_…`) with thinking between the calls, so the replayed thinking blocks and signatures were accepted; Code Interpreter also ran |
| Stop while thinking streams | Cancel answered 202, the stream stopped, the turn ended `CANCELED` |
| An answer written by the model | The test accounts read no documents, so each grounded turn ended with the fixed "no information" answer, as it did on `cx/gpt-6-luna` for the same question; the owner then chatted with Claude on staging and confirmed the answers |
| Usage | 8 calls, 127,248 input and 4,814 output tokens, no cache reads, recorded under provider `Claude` |

The run found two defects, fixed in PR 2: a stopped turn logged `Error processing streaming response` and `Aggregation Error` with a stack trace from Spring AI, and the usage of `claude-haiku-5-5` and `claude-sonnet-5-5` was unpriced (`unknown_cost_calls` 8) because the pinned LiteLLM metadata predated them. At LiteLLM's price for Haiku 5.5 ($0.10 and $0.50 per million tokens) the run cost about $0.015.

Not reached: a forced tool call after earlier thinking, and Web search, which is not configured on staging.
