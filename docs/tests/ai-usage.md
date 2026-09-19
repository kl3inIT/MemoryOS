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
| HTTP paths and schemas are in the checked-in contract | `api/src/test/java/io/memoryos/api/OpenApiContractTest.java` |
| The page shows known and unpriced spend, ranks people and opens a person's detail | `web/src/features/usage/ai-costs.test.tsx` |
| Pricing forms keep unknown pricing distinct from 0/0 and carry the optional cache-read price | `web/src/features/models/model-catalog.test.ts` |

Screens were reviewed at 1280 and 390 pixels with realistic fixtures before merge; no live provider billing reconciliation is claimed.
