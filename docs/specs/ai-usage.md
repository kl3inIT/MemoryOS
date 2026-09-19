# AI usage and costs

The `usage` capability records every AI call MemoryOS makes into a daily ledger and reports it to model managers as "AI costs" (vi: "Chi phí AI"). Delivered by [MEM-98](../increments/active/mem-98-ai-costs/design.md); limits (MEM-123), export (MEM-139) and per-call external-data records (MEM-134) build on it.

## Ledger

`ai_usage` (V85) is a daily UTC rollup, as Onyx `user_usage`: one accumulating row per Tenant, actor, day, flow, provider name, model name and data boundary (`UNIQUE NULLS NOT DISTINCT`), upserted by `AiUsageRecorder`. Each row carries calls, input, output and cache-read tokens, image count, audio seconds, known cost in USD and `unknown_cost_calls` (never more than `calls`).

- **Written synchronously, never dropped.** Chat writes in the transaction that finishes the turn; other flows write when their provider call returns. Onyx queues samples and drops them under pressure; MemoryOS does not, because limits (MEM-123) will block on these totals.
- **Unknown cost stays unknown.** A call without a price adds its tokens and one `unknown_cost_calls`; it never adds 0 to the cost. A model priced 0/0 is priced and costs 0.
- **Names and boundary at call time.** Provider and model names and the provider's data boundary (MEM-102) are copied into the row; provider and model configuration IDs are kept without foreign keys, so renaming, relabelling or deleting catalog entries leaves history unchanged.
- **Actor is nullable** for system work (document indexing embeddings). The actor reference does not cascade.
- Per-message usage on `chat_message` stays; the ledger is the aggregate.

## Flows

`CHAT`, `CHAT_NAMING`, `DEEP_RESEARCH`, `EMBEDDING_QUERY`, `EMBEDDING_INDEXING`, `IMAGE_GENERATION`, `IMAGE_EDIT`, `SPEECH_TO_TEXT`, `TEXT_TO_SPEECH`. A new AI task adds a value to `AiUsageFlow` and the table's check constraint.

| Flow | Captured by | Usage |
| --- | --- | --- |
| Chat, Deep research | `ChatTurnService` when the turn settles (completed, stopped or failed), summed over every `ChatModelGuard` of the turn | Provider-reported input, output and cache-read tokens; cost from the model price |
| Conversation naming | `ChatTurnService.recordNaming` after the title call | Same, on the naming model |
| Search and indexing embeddings | `ValidatedEmbeddingService` per batch with a known caller | Reported tokens; priced by `memoryos.search.embedding-input-price-per-million`, unknown when unset |
| Image generation and editing | `ImageProviderClient` after a delivered image | One image; cost unknown |
| Speech-to-text | `VoiceTranscriptionService` once when the session closes | Seconds from the audio received (16-bit mono 24 kHz); cost unknown |
| Text-to-speech | `VoiceSynthesisService` per synthesis | One call; cost unknown |

## Pricing

Model prices are USD per million tokens on the catalog model (`ModelSettings.Pricing`): input, output and an optional cache-read rate. Prompt tokens include cached ones, as in Onyx, LiteLLM and 9router, so a call costs `(input − cached) × input rate + cached × cache-read rate + output × output rate`; without a cache-read rate cached input costs the input rate. Embabel prices every input token at the input rate; `ChatModelPricing.cacheDiscount` subtracts the difference once the turn's cached tokens are known. The installed catalog (`known-models.json`) carries LiteLLM's `cache_read_input_token_cost` as `cachedInputPerMillion`. Local models (vLLM, Ollama) have no price until a manager sets one; 0/0 declares them free.

All costs are estimates from provider-reported usage and catalog prices, not provider invoices.

## HTTP

Every endpoint requires `MODELS_MANAGE` (implied by `SYSTEM_ADMIN`). `from` and `to` are inclusive UTC dates, at most 366 days apart. Summary, daily and breakdown accept optional `model` and `flow` filters.

| Method and path | Contract |
| --- | --- |
| `GET /api/ai-costs/summary?from&to` | `AiCostSummary`: cost, External cost, calls, unknown-cost calls, input/output/cache-read tokens, images, audio seconds, active people |
| `GET /api/ai-costs/daily?from&to&split=BOUNDARY\|MODEL\|NONE` | `AiCostDay[]`: one entry per day and series |
| `GET /api/ai-costs/breakdown?from&to&by=ACTOR\|GROUP\|MODEL\|FLOW\|PROVIDER&limit` | `AiCostRow[]` ranked by cost, at most `limit` (1–200, default 50); system work is one `SYSTEM` row; a person counts in every Group they belong to at query time |
| `GET /api/ai-costs/detail?from&to&actorId` or `&system=true` | `AiCostDetail`: one person's (or system work's) summary, daily cost by model, and rows by model, flow and provider |

## Administration

"Monitoring › AI costs" (`/admin/ai-costs`, vi "Theo dõi › Chi phí AI"): period (last 7 days, last 30 days, this month, last month), one summary strip, daily spend stacked by Internal/External or by model, breakdown tabs by user, Group, model, flow and provider with proportion bars, and a per-user modal as Onyx `UserUsageDetailModal`. Unpriced calls link to the Models page, where the model editor has an optional cache-read price. Copy follows Onyx, Orca and the OpenAI and LangChain usage screens.
