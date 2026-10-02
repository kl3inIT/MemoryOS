# Plan: reasoning per task model

Design: [design.md](design.md).

- [x] V143 `model_flow_default.reasoning_effort`, nullable, checked against the four levels.
- [x] `ModelFlow.defaultEffort()`. `FlowModelDefault` and `FlowView` carry the stored and the effective level. `setFlowDefault` takes the level and audits it.
- [x] API: `ChatModelFlowResponse.reasoningEffort` and the optional `reasoningEffort` parameter of `setChatModelFlow`. Regenerate `openapi.yml` and the web client.
- [x] The task callers apply the level: naming (`ChatModelExecutor`), the question check (`GroundingClassifier`), the minutes (`TranscriptSummarizer`) and corrections (`TranscriptCorrector`). The selection carries it (`ModelBinding.forTask`), so `ModelCalls` keeps one structured call and `generateReasonedObject` is gone.
- [x] Models page: the rows use assistant-ui's Model selector; task rows get its Thinking row and the level pill, and a choice saves at once.
- [x] Tests:
  - repository round trip and migration;
  - service default and audit;
  - each caller's helper and reasoned paths;
  - the web row saves a level.
- [x] Docs: `docs/specs/chat-models.md` (task models), the test matrix, roadmap.
- [ ] UI screenshots for owner approval; staging check.
