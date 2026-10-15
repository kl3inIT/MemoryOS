# Implementation plan

Design: [design.md](design.md).

## 1. Cache on the request

- [x] `ModelBinding.PROMPT_CACHE_KEY`. The executor puts the conversation ID on the answer's options.
- [x] OpenAI converter sets `promptCacheKey`; `ResponsesRequestBuilder` sends `prompt_cache_key`.
- [x] Anthropic converter adds `withAnthropicCaching` (system, tools, history) to answer calls only.

## 2. Cost and measurement

- [x] `ModelPricing` prices cache writes with the adapter's multiplier (Anthropic 1.25); `ModelAccounting` applies it.
- [x] `ModelGuard` logs `ai.model.call.completed` per call: tokens, cache read and write, tool and system fingerprints.

## 3. Verification

- [x] Adapter tests: an Anthropic answer request marks the tools and the last message; a helper request
  marks nothing; Chat Completions and Responses requests carry `prompt_cache_key`.
- [x] Accounting test: written input costs 1.25 times the input rate on Anthropic.
- [ ] Staging: a Claude conversation reads from the cache from its second call; luna's per-call read share; the
  fingerprints explain a miss.
- [x] Spec `chat-models`, `ai-usage` and the observability guideline.
