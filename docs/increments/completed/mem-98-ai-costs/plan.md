# Implementation plan

One pull request on `kl3inIT/mem-98-ai-costs`.

## 1. Schema

- [x] V85: `ai_usage` daily rollup with its unique key, nullable actor, boundary, names at call time and `unknown_cost_calls`.

## 2. Ledger and capture

- [x] `usage` module: `AiUsageFlow`, `AiUsageRecorder` (upsert), `AiCostService` and `AiCostQueries`.
- [x] Chat, naming and deep research summed from `ChatModelGuard` usage when the turn settles (not an Embabel listener, see design).
- [x] Embedding query and indexing, image generation and editing, speech-to-text and text-to-speech recorders.
- [x] Cache-read price on catalog models and in `known-models.json`; turn cost corrected by `ChatModelPricing.cacheDiscount`.
- [x] Tests: accumulation, separate rows, system work, catalog deletion, queries, cache pricing, multi-cycle turn with naming, each non-chat flow.

## 3. HTTP and client

- [x] `/api/ai-costs` summary, daily, breakdown and detail with `MODELS_MANAGE`; OpenAPI snapshot; Hey API client.

## 4. Web

- [x] "Theo dõi › Chi phí AI" sidebar entry and route; summary strip, daily chart, breakdown tabs, per-user modal; cache-read price in the model editor; vi/en strings; tests; screenshots at 1280px and 390px.

## 5. Documentation and gates

- [x] `docs/specs/ai-usage.md`, `docs/tests/ai-usage.md`, architecture, model spec and AGENTS.md links.
- [x] `gradlew clean check`; web lint, typecheck, tests; [PR #241](https://github.com/kl3inIT/MemoryOS/pull/241) merged after CI and CodeRabbit findings were fixed.
