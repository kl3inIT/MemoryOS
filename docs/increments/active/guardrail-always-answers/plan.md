# Implementation plan

- [x] `GroundingClassifier`: one-label verdict, 1,024 output tokens, lenient reading, no guess without a verdict.
- [x] `ChatTurnService`: task model, then conversation model; with no verdict the turn is answered and
  `ChatTurnOptions.topicRules` gives the answer model the enabled topics.
- [x] `CHAT_GUARDRAIL_UNAVAILABLE` removed with its web notice; no new refusal reason and no migration.
- [x] Tests: `ChatGroundedTurnTest`, `ChatGuardrailRulesTest`, `GroundingClassifierTest`, `TurnFailureTest`.
- [x] Chat spec, test matrix and the MEM-203 increment.
- [ ] CI; merge; on staging, ask the questions that failed on 2026-10-01 and watch `chat.guardrail.unavailable`.
