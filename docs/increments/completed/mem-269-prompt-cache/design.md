# Prompt cache on every provider, measured per call

Linear: [MEM-269](https://linear.app/memory-os/issue/MEM-269). Plan: [plan.md](plan.md). Related:
[MEM-260](https://linear.app/memory-os/issue/MEM-260) (system prompt order) and
[MEM-264](../../active/mem-264-mcp-tool-policy/design.md) (MCP tools in the request).

## Problem

Over the 14 days before 2026-10-15, staging read 8.9% of Chat input from the provider's prompt cache on
`cx/gpt-6-luna` (9Router), 38.5% on `ocg/deepseek-v4-flash` and nothing on `claude-haiku-5-5`. Probes against the same
providers with a stable 6k-token prefix and 40 tools read about 74% (luna through 9Router) and 100% (native OpenAI and,
once marked, Anthropic):

| Case | OpenAI gpt-6-luna (native) | Claude Haiku 5.5, unmarked | Claude Haiku 5.5, `cache_control` |
| --- | --- | --- | --- |
| Same request again | 7334 of 7337 read | 0 | 15987 read |
| History grows, same tools | 7334 read | 0 | 15987 read |
| One tool appended | 0 | 0 | 0 read, 16260 written |
| First tool's description changed | 0 | 0 | 0 read, 15892 written |

So:

- Anthropic caches nothing without `cache_control`, and MemoryOS sends none.
- OpenAI caches the longest stable prefix by itself. `prompt_cache_key` keeps one conversation's requests on the same
  cache.
- Any change to the tool list loses the whole cached prefix on every provider measured. The tool list sits before the
  system prompt and the history.
- The usage ledger keeps one daily total of cached reads. Nothing shows which call missed, or why.

## Decision

Use what Embabel and Spring AI already ship; write only the measurement.

- **Anthropic.** An answer call carries Embabel's `AnthropicCachingConfig` (system prompt, tools and conversation
  history) through `LlmOptions.withAnthropicCaching`. The `AnthropicOptionsConverter` the adapter already calls turns it
  into Spring AI's `AnthropicCacheOptions`, which places the breakpoints. A helper call (thinking off: query rewrites,
  filters, titles, the guardrail check) carries none, so a prompt read once is not written at the write premium.
- **OpenAI and OpenAI-compatible.** An answer call carries the conversation's ID as `prompt_cache_key`. It travels as an
  Embabel options extension (`ModelBinding.PROMPT_CACHE_KEY`, like `OUTPUT_SCHEMA`). The converter sets Spring AI's
  `OpenAiChatOptions.promptCacheKey` for Chat Completions, and `ResponsesRequestBuilder` sends it on the Responses route.
  The ID is an opaque UUID.
- **Cost.** Anthropic bills a five-minute cache write at 1.25 times the input rate. The Anthropic adapter prices it so,
  through `ModelPricing`, beside the existing cache-read discount. Other providers bill writes at the input rate, so
  nothing changes for them. Token totals keep counting written input as input.
- **Per-call measurement.** `ModelGuard` logs `ai.model.call.completed` for every call that reports usage, with:
  - the model, whether the call streamed, and its input, cached-read and cached-write tokens;
  - the number of tools and of messages;
  - short SHA-256 fingerprints of the tool definitions and of the system messages.

  It logs no content. When a call misses, its fingerprints show whether the tools or the system prompt changed since
  the call before; equal fingerprints point at the history or the provider.

The executor adds its tools in a fixed order and each MCP server's tools by name. The servers a turn selected now
follow their ID rather than the order the request listed them, so the tool list does not change between turns.

## Not in this increment

- Moving per-turn content (date, user, memory) to the end of the system prompt: MEM-260.
- MCP tool search or unfolding. When it lands, Anthropic `tool_addition` and OpenAI `additional_tools` keep the cache
  across a tool change (probed: 13056 of 13247 read on Claude, 5923 of 6090 on OpenAI); a changed top-level list does
  not.
- Cache warming and one-hour retention.
- A configurable cache-write price per model.
