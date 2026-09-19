# Implementation plan

One pull request on `kl3inIT/mem-98-ai-costs`.

## 1. Schema

- [ ] V85: `ai_usage` daily rollup with its unique key, nullable actor (`ON DELETE SET NULL`), boundary, names at call time, `unpriced_calls`; pricing columns for cache-read and per-image rates; Tenant price overrides.

## 2. Ledger and capture

- [ ] `AiUsageFlow`, `AiUsageRecorder` (upsert), repository aggregates.
- [ ] Embabel `AgenticEventListener` accumulating `LlmInvocationEvent` per tagged process; chat, naming and deep research tag their processes and settle on finish, cancel and failure.
- [ ] Recorders in embedding (query and indexing), image generation and editing, speech-to-text and text-to-speech.
- [ ] Pricing: cache-read and image rates, overrides, cache tokens priced once.
- [ ] Tests: multi-cycle tool turn, cancelled and failed turns, unpriced model, cache-read, UTC day boundary, deleted actor, each non-chat flow.

## 3. HTTP and client

- [ ] `/api/ai-costs` summary, daily, breakdown, actor detail with `MODELS_MANAGE`; OpenAPI snapshot; Hey API client.

## 4. Web

- [ ] "Theo dõi › Chi phí AI" sidebar entry and route; summary strip, daily chart, breakdown tabs, person detail; vi/en strings; tests; screenshots at 1280px and 390px.

## 5. Documentation and gates

- [ ] `docs/specs/chat-models.md`, `docs/tests/chat.md`, AGENTS.md active increments; `gradlew clean check`; web lint, typecheck, tests.
