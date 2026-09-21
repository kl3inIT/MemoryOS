# AI usage and costs

The `usage` capability records every AI call MemoryOS makes into a daily ledger and reports it to model managers as "AI costs" (vi: "Chi phí AI"). Delivered by [MEM-98](../increments/completed/mem-98-ai-costs/design.md); usage reports ([MEM-139](../increments/completed/mem-139-usage-report/design.md)) build on it; limits (MEM-123) and per-call external-data records (MEM-134) will.

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

Every endpoint except `/mine` requires `MODELS_MANAGE` (implied by `SYSTEM_ADMIN`). `from` and `to` are inclusive UTC dates, at most 366 days apart. Summary, daily and breakdown accept optional `model` and `flow` filters.

| Method and path | Contract |
| --- | --- |
| `GET /api/ai-costs/summary?from&to` | `AiCostSummary`: cost, External cost, calls, unknown-cost calls, input/output/cache-read tokens, images, audio seconds, active people |
| `GET /api/ai-costs/daily?from&to&split=BOUNDARY\|MODEL\|NONE` | `AiCostDay[]`: one entry per day and series |
| `GET /api/ai-costs/breakdown?from&to&by=ACTOR\|GROUP\|MODEL\|FLOW\|PROVIDER&limit` | `AiCostRow[]` ranked by cost, at most `limit` (1–200, default 50); system work is one `SYSTEM` row; a person counts in every Group they belong to at query time |
| `GET /api/ai-costs/mine?from&to` | `AiCostDetail` of the caller only (`CHAT_READ`), shown in Settings → Usage with the prices of the models the caller may use |
| `GET /api/ai-costs/detail?from&to&actorId` or `&system=true` | `AiCostDetail`: one person's (or system work's) summary, daily cost by model, and rows by model, flow and provider |
| `POST /api/ai-costs/reports` `{from, to}` | 202 `UsageReport` in `PENDING`; the Worker builds it |
| `GET /api/ai-costs/reports` | The Tenant's 50 newest `UsageReport`s, whoever requested them |
| `GET /api/ai-costs/reports/{reportId}/content` | The ZIP of a `READY` report (`application/zip`, attachment); 404 for any other state, id or Tenant |

## Usage reports

A usage report is Onyx's usage report export for one period, bounded like the page (at most 366 UTC days, no "All time"). A model manager requests it; the Worker task `memoryos-ai-usage-report-v1` claims the oldest `PENDING` report every 5 s with a 10-minute lease, builds it and stores the ZIP through `ObjectWriteService`. A failed attempt returns the report to the queue; the third failure, or a lease that lapses on the third attempt, marks it `FAILED`. A Worker whose lease lapsed cannot mark the report ready.

The ZIP holds:

- `usage_by_user.csv`: one row per `ai_usage` rollup row (person or system work, UTC day, flow, provider, model, data boundary) with the person's current Groups, calls, unpriced calls, tokens, images, audio seconds and cost in USD. Cells from user-controlled text that begin with `=`, `+`, `-`, `@`, tab or carriage return are prefixed with `'`, as in Onyx. The file starts with a UTF-8 byte-order mark so spreadsheets read Vietnamese names.
- `users.csv`: every Tenant member with their Groups, whether the membership is active and whether they used AI in the period.
- `usage_report.pdf`: a Vietnamese review pack built from the same rows as the CSV, so the totals reconcile. The cover has three headline figures, the share of active members who used AI, a summary, how costs are counted, and the top three models. Later pages show daily active people, daily cost, cost by model, the heaviest users, cost by task, by Group and by data boundary, and the active members who did not use AI (25 listed, the rest counted). Top lists fold everything after the tenth person, or the eighth model, task or Group, into "Khác (n)", but never fold a single row. It embeds Hanken Grotesk (SIL OFL, `core/src/main/resources/fonts`); a character the typeface cannot draw prints as `?`. If rendering fails, the ZIP ships without the PDF and the report says so.

Chat message metadata (Onyx `chat_messages.csv`) is not exported; message-level history belongs to MEM-125. Money stays in USD.

## Administration

"Monitoring › AI costs" (`/admin/ai-costs`, vi "Theo dõi › Chi phí AI"): period (last 7 days, last 30 days, this month, last month), one summary strip, daily spend stacked by Internal/External or by model, breakdown tabs by user, Group, model, flow and provider with proportion bars, a per-user modal as Onyx `UserUsageDetailModal`, and "Usage reports" at the foot of the page as in Onyx: a Generate report menu over the page's periods, a pending row polled every 3 s with a hint after 20 s, and a row per report with its requester, time and a download link. Unpriced calls link to the Models page, where the model editor has an optional cache-read price. Copy follows Onyx, Orca and the OpenAI and LangChain usage screens.
