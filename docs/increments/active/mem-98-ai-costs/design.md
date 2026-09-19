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

### Ledger

- `ai_usage`, a daily UTC rollup as in Onyx: one row per `(tenant, actor, day, flow, model_configuration_id, provider_id)`, upserted. Columns: calls, input, output and cache-read tokens, image count, audio seconds, known cost in USD, and the number of calls whose cost is unknown.
- Differences from Onyx, each for a stated reason:
  - **Written synchronously, never dropped.** MEM-123 blocks spending on these totals, so a lost sample would let a Tenant exceed its limit. Chat writes in the transaction that finishes the turn; other flows write where their call completes.
  - **`data_boundary` is part of the row,** copied from the provider at call time (MEM-102), so a later relabel does not rewrite history and spend splits into Internal and External.
  - **Unknown cost stays unknown.** A call without pricing adds its tokens and increments `unpriced_calls`; it never adds 0 to the cost. Totals show both.
- Actor is nullable for system work (document indexing embeddings), `ON DELETE SET NULL`. Model and provider references are kept as IDs plus the model and provider names at call time, so deleting a model keeps its history readable.
- Per-message `chat_message` usage stays; the ledger is the aggregate, not a replacement.

### Flows

`CHAT`, `CHAT_NAMING`, `DEEP_RESEARCH`, `EMBEDDING_QUERY`, `EMBEDDING_INDEXING`, `IMAGE_GENERATION`, `IMAGE_EDIT`, `SPEECH_TO_TEXT`, `TEXT_TO_SPEECH`. A new AI task adds a flow value (MEM-129 intent routing, for example).

### Capture

- Model calls inside an Embabel agent process (chat, naming, deep research) come from `LlmInvocationEvent`. MemoryOS already runs each of these in a process it creates; the process is tagged with its Tenant, actor and flow when it starts, and a listener accumulates the invocations of that process. The accumulated usage is written when the turn or task settles, including cancelled and failed turns, which keep what they used.
- Embeddings, image calls and voice calls do not run in an agent process; their services record through the same `AiUsageRecorder` after the provider call returns, using provider-reported usage where it exists (embedding tokens, image count, audio seconds).
- To verify during implementation: that every chat, naming and research call goes through a tagged process, and which usage the image and voice providers actually report.

### Pricing

- `ModelSettings.Pricing` gains an optional cache-read rate and an optional per-image rate. Cache-read tokens come from the provider's native usage and are priced once: `(input − cacheRead) × input + cacheRead × cacheReadRate`.
- A Tenant price override by provider and model name, as Onyx `ModelCostOverride`, takes precedence over the model settings.

### Administration: "Chi phí AI"

- New sidebar section **"Theo dõi"** (monitoring, like Onyx's performance group) with **"Chi phí AI"** at `/admin/ai-costs`. MEM-134's external-data log and MEM-125's query history join this section later.
- Summary: cost (with the External share), AI calls, tokens (input, output, cache-read), active people, and **"Chưa tính được giá"** — unpriced calls with a link to the Models page.
- Daily cost chart, stacked by data boundary or by model.
- Breakdown tabs **Người dùng / Group / Model / Tác vụ / Nhà cung cấp**, sorted by cost with proportion bars; model and task filters; a person opens a detail sheet (daily cost, by model, by task, by provider).
- Period: last 7 days, last 30 days, this month, last month. Access: `MODELS_MANAGE` (implied by `SYSTEM_ADMIN`).

## HTTP

| Method and path | Contract |
| --- | --- |
| `GET /api/ai-costs/summary?from&to` | totals, External share, unpriced calls, active people; `MODELS_MANAGE` |
| `GET /api/ai-costs/daily?from&to&split=boundary\|model` | one entry per UTC day |
| `GET /api/ai-costs/breakdown?from&to&by=actor\|group\|model\|flow\|provider&model&flow&limit` | ranked rows, bounded |
| `GET /api/ai-costs/actors/{actorId}?from&to` | one person's detail |

Group totals count a person in every Group they belong to at query time; the page says so.

## Out of scope

Period limits, the "my usage" page and the limit banner (MEM-123); CSV and PDF export and scheduled reports (MEM-139); per-call external-data records (MEM-134); query history (MEM-125); billing and invoices.
