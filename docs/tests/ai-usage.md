# AI usage and costs verification matrix

Contract: [AI usage and costs](../specs/ai-usage.md).

| Requirement | Durable verification |
| --- | --- |
| Calls of one day accumulate into one row; a call without a price counts as unknown and adds no cost | `core/src/test/java/io/memoryos/usage/AiUsageRecorderTest.java` — `callsOfOneDayAccumulateAndUnknownCostIsCountedNotAddedAsZero` |
| Day, flow, model and boundary each start a separate row; system work without actor or boundary merges into one row (`NULLS NOT DISTINCT`) | `AiUsageRecorderTest.dayFlowModelAndBoundaryStartSeparateRows`, `systemWorkWithoutActorOrBoundaryMergesIntoOneRow` |
| Deleting catalog entries leaves history unchanged; invalid usage is rejected | `AiUsageRecorderTest.catalogDeletionDoesNotTouchHistoryAndInvalidInputIsRejected` |
| Totals separate known, External and unknown cost and count active people; daily series split by boundary or model; breakdowns rank by cost and label people, Groups, models, flows and providers | `core/src/test/java/io/memoryos/usage/AiCostQueriesTest.java` |
| Cached input costs the cache-read rate and defaults to the input rate | `core/src/test/java/io/memoryos/chat/catalog/ChatModelPricingTest.java` |
| A Chat turn and its naming call reach the daily ledger with provider, boundary and cost | `api/src/test/java/io/memoryos/api/chat/ChatSessionApiIntegrationTest.java` — `aiUsageRecordsTheTurnAndItsNamingToTheDailyLedger` (Spring API, PostgreSQL, synthetic provider) |
| `/api/ai-costs` reports the ledger only to model managers; `/mine` returns only the caller's own usage to any member | `ChatSessionApiIntegrationTest.aiCostsReportTheLedgerOnlyToModelManagers` |
| Settings → Usage shows the caller's spend, tokens per model, model prices with unpriced models and no budget | `web/src/features/usage/my-usage-page.test.tsx` |
| Embedding batches record reported tokens only for a known caller and price them when configured | `core/src/test/java/io/memoryos/retrieval/ValidatedEmbeddingServiceTest.java` — `recordsReportedTokensForAKnownCallerOnlyAndPricesThemWhenConfigured` |
| Only a delivered image is recorded | `core/src/test/java/io/memoryos/chat/tools/GenerateImageToolTest.java` — `onlyADeliveredImageIsAddedToAiUsage` |
| A closed dictation session adds its recorded seconds once | `core/src/test/java/io/memoryos/chat/voice/VoiceTranscriptionServiceTest.java` — `aClosedSessionAddsItsRecordedSecondsToAiUsageOnce` |
| A usage report is requested, built by the Worker step and downloaded only by model managers; the ZIP holds the three files, neutralizes a formula in data and exports system work | `ChatSessionApiIntegrationTest.usageReportsAreQueuedBuiltAndDownloadedOnlyByModelManagers` (Spring API, PostgreSQL, in-memory object storage) |
| A claim leases the oldest report once; failures requeue until the third attempt; a lapsed lease is reclaimed and the stale Worker cannot finish; reports stay in their Tenant | `core/src/test/java/io/memoryos/usage/UsageReportRepositoryTest.java` |
| The PDF draws Vietnamese across the cover and section pages, numbers pages, survives an undrawable character, and is one page for an empty period; top lists fold into "Other" but never a single row | `core/src/test/java/io/memoryos/usage/report/UsageReportPdfTest.java` |
| The CSV guard prefixes every formula character Onyx guards and leaves ordinary text alone | `core/src/test/java/io/memoryos/usage/report/UsageReportCsvTest.java` |
| The Usage reports section lists ready, building and failed reports, blocks a second request while one builds, requests the chosen period and offers a retry | `web/src/features/usage/usage-reports.test.tsx` |
| A person, Group and Tenant budget each bind and free on their own window; system work never counts; an unpriced call adds no cost; a disabled limit refuses nothing; the tightest budget is the one a person sees | `core/src/test/java/io/memoryos/usage/AiUsageLimitServiceTest.java` |
| Limits are set only by model managers, the change is audited, a spent budget refuses a turn with 429 and `Retry-After`, and indexing fills no one's budget | `ChatSessionApiIntegrationTest.spendingLimitsAreSetByModelManagersAndRefuseATurnWithTheBudgetSpent` |
| The Limits section reads a budget beside its usage, keeps a budget when switched off and refuses to save a limit with no budget | `web/src/features/usage/usage-limits.test.tsx` |
| A member sees the budget that binds them, and nothing when the Tenant sets none | `web/src/features/usage/my-usage-page.test.tsx` |
| HTTP paths and schemas are in the checked-in contract | `api/src/test/java/io/memoryos/api/OpenApiContractTest.java` |
| The page shows known and unpriced spend, ranks people and opens a person's detail | `web/src/features/usage/ai-costs.test.tsx` |
| Pricing forms keep unknown pricing distinct from 0/0 and carry the optional cache-read price | `web/src/features/models/model-catalog.test.ts` |

Screens were reviewed at 1280 and 390 pixels with realistic fixtures before merge; no live provider billing reconciliation is claimed.
