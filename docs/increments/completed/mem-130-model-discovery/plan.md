# MEM-130 plan

- [x] Staging catalog corrected on 2026-09-19 for all six models (limits and prices per the issue table; revisions bumped).
- [x] Reported models carry published and catalog specs; OpenAI-compatible field parsing; 16 MiB / 1,000 cap.
- [x] Catalog extended to Gemini, xAI, DeepSeek and Mistral; bootstrap uses catalog specs.
- [x] `CHAT_MODEL_OUTPUT_LIMIT` and `failureCode` on replies; `CHAT_RESEARCH_MODEL_UNSUPPORTED` and a disabled toggle.
- [x] Discovery dialog: search, specs columns, bulk add of complete models, prefilled manual add.
- [x] Onyx defaults: `maxOutputTokens` optional end to end (no cap sent), 32,000-token fallback window and tool
  calling for undescribed models, every reported model added without typing; 9Router `capabilities.tools`.
- [x] Tests: `OpenAiReportedModelsTest`, `ChatModelCatalogConfigurationTest`, `OpenAiResponsesChatModelTest`, `ChatSessionApiIntegrationTest` (reported models, research model), web chat and models suites.
- [x] Staging: discover an OpenRouter provider and add a model without typing its specs; a luna run_python turn completes. (2026-09-20: owner acceptance on staging.)
