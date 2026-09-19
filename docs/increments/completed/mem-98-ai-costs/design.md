# MEM-98 — AI usage and costs

Linear: [MEM-98](https://linear.app/memory-os/issue/MEM-98). Foundation for [MEM-123 limits](https://linear.app/memory-os/issue/MEM-123), [MEM-134 external data gate](https://linear.app/memory-os/issue/MEM-134) and [MEM-139 reports](https://linear.app/memory-os/issue/MEM-139).

## Problem

AI spend is recorded only per assistant message (`chat_message.input_tokens`, `output_tokens`, `cost_usd`, V19) and capped only per turn (`ChatAdmissionLedger`, `MEMORYOS_CHAT_COST_BUDGET_USD`). Nothing answers "how much did the Tenant, a Group, a person, a model or a task spend this month". Conversation naming, embeddings, image generation and editing, and voice record no usage at all.

## Reference

**Onyx** (read in `.tmp/onyx`):

- `user_usage` (`backend/onyx/db/models.py`, `db/user_usage.py`) is a daily UTC rollup, one accumulating row per `(user, window_start, model, flow, provider, incognito)`, upserted with `ON CONFLICT DO UPDATE`. Columns: input, output, cache-read and cache-creation tokens, `cost_cents`. `user_id` is `ON DELETE SET NULL` so deleted users keep their spend; `provider` stores `''` instead of NULL so the unique key deduplicates.
- `tracing/processors/user_usage_processor.py` queues every priced generation span, drains in batches every 2 seconds or 200 records, computes cost at write time with `compute_cost_cents` (cache-read rate, image cost, `(provider, model)` overrides) and drops samples when the queue is full or the database fails.
- `tracing/flows.py` names about twenty flows (chat response, history summarization, query rephrase and expansion, source and time filters, section relevance, chat naming, memory update, contextual RAG, image summarization, image generation, LLM gateway…).
- Admin › Usage: summary (workspace spend, total tokens including cache reads, active users, top spender), users sorted by spend with model and flow filters, per-user detail (spend, input, output, cache reads and writes, daily spend, breakdown by model, flow and provider). Onyx places it with the other monitoring pages (`admin/performance/usage`).

**Frameworks** (checked in the Embabel 1.5.1 jar and the Spring AI 2.0 reference):

- Embabel publishes `LlmInvocationEvent` for every model call inside an agent process: `LlmInvocation` carries `LlmMetadata` (model, provider, pricing), `Usage`, agent name, timestamp, running time and `cost()`. `AgenticEventListener` receives it. `PerTokenPricingModel` has only input and output rates.
- Spring AI exposes `ChatResponseMetadata.getUsage()` and `getNativeUsage()` (OpenAI cached and reasoning tokens), `EmbeddingResponseMetadata` usage, and Micrometer `gen_ai_client_*` metrics. It stores and prices nothing.

**Mobbin:** LangChain Usage (summary strip, spend over time, breakdown tabs by agent, user, tool and model), OpenAI Platform Usage (daily spend bars with an input/output tooltip), Cursor Usage (spend by model), Framer Usage (ranked bars per feature, model and user), ClickUp AI Usage (breakdown by AI task).

## Decisions

The implemented contract, flows and HTTP API are in [AI usage and costs](../../../specs/ai-usage.md); this section keeps the reasoning.

### Ledger

- `ai_usage`, a daily UTC rollup as in Onyx, keyed by Tenant, actor, day, flow, provider name, model name and data boundary (`UNIQUE NULLS NOT DISTINCT`, PostgreSQL 18), upserted.
- Differences from Onyx, each for a stated reason:
  - **Written synchronously, never dropped.** MEM-123 blocks spending on these totals, so a lost sample would let a Tenant exceed its limit.
  - **`data_boundary` is part of the row,** copied from the provider at call time (MEM-102), so a later relabel does not rewrite history and spend splits into Internal and External.
  - **Unknown cost stays unknown.** Onyx falls back to `DEFAULT_LLM_INPUT_COST_PER_MTOK` (0 by default), which silently turns every unpriced model, including local ones, into $0. MemoryOS counts `unknown_cost_calls` instead; a manager who wants a local model to be free prices it 0/0.
- Model and provider are kept as IDs without foreign keys plus their names at call time, so deleting a model keeps its history readable.

### Capture

- **Guards, not an Embabel listener.** The design first planned an `AgenticEventListener` on `LlmInvocationEvent`. Every chat, naming and research inference already passes through a `ChatModelGuard`, which sees the provider `Usage` (including `getCacheReadInputTokens()`) and settles with the turn in `ChatTurnPersistence`. Summing the guards writes the ledger in the same transaction as the message and needs no process tagging.
- Embeddings, image and voice services record after the provider call returns. Image and voice providers report no price, so those calls count as unknown cost; embeddings are priced by `memoryos.search.embedding-input-price-per-million` when set.

### Pricing

- `ModelSettings.Pricing` gains an optional cache-read rate (Onyx `cache_read_cost_per_mtok`), defaulting to the input rate. The installed catalog carries LiteLLM's cache-read prices. Onyx, Orca, LiteLLM and 9router all treat prompt tokens as cache-inclusive; the cost formula follows them.
- **No Tenant price-override table.** Onyx needs `ModelCostOverride` because its prices come from LiteLLM; MemoryOS catalog prices are already manager-editable per model.
- No per-image rate: image providers report no price and the image catalog carries none yet.

### Administration: "Chi phí AI"

- Sidebar section "Theo dõi" (monitoring, like Onyx's performance group) with "Chi phí AI" at `/admin/ai-costs`.
- One summary strip (LangChain, ElevenLabs), daily spend chart (OpenAI Platform), breakdown tabs (LangChain "By …"), per-user centered modal (Onyx `UserUsageDetailModal`) rather than a side sheet.
- Copy is taken from Onyx, Orca ("Est. spend"), OpenAI ("Requests") and LangChain rather than written fresh; "input", "output" and "cache reads" stay English in Vietnamese.

## Out of scope

Period limits, the "my usage" page and the limit banner (MEM-123); CSV and PDF export and scheduled reports (MEM-139); per-call external-data records (MEM-134); query history (MEM-125); billing and invoices.
