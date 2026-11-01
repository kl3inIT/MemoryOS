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
- [x] Chat composer: the Thinking row and the level pill in `ChatModelPicker`; the first send pins a level chosen before the conversation existed.
- [x] Docs: `docs/specs/chat-models.md` (task models), the test matrix, roadmap.
- [x] UI screenshots for owner approval (2026-10-03); CI; merge (#491).
- [x] Staging: V143 applied, each task row shows its level, a level saved on the Models page reaches the task's call, and a level picked in the composer before the first question applies to the first answer.
  2026-10-28 on `87eedb29`: V143 is in `flyway_schema_history`; `GET /api/chat/model-flows` returns a level for each of the six tasks (the minutes `MEDIUM`, the others `OFF`); saving `LOW` on the naming task returned it with revision 10, and `OFF` was put back (revision 11). Nine conversations since 2026-10-03 carry a pinned `LOW`. That the level reaches the provider request was not observed: 9Router has kept no request bodies since July, so it rests on the unit tests above.

## Staging acceptance (2026-10-19)

V143 applied; `GET /api/chat/model-flows` returns each flow's level and the Models page shows it ("Vừa" on the minutes). A saved level reaching the task's call and the composer check remain.
