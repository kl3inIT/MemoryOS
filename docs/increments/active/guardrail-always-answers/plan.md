# Implementation plan

- [x] `GroundingClassifier`: one-label verdict, 1,024 output tokens, lenient reading, no guess without a verdict.
- [x] `ChatTurnService`: task model, then conversation model; `unchecked` reply with topics, a question without.
- [x] `ChatMessage.UNCHECKED`, `ChatRefusals.unchecked`, V137; `CHAT_GUARDRAIL_UNAVAILABLE` removed.
- [x] Web: the reason label for `unchecked` with a manager link; the removed failure notice.
- [x] Tests: `ChatGroundedTurnTest`, `GroundingClassifierTest`, `TurnFailureTest`, `chat.spec.ts` for both roles.
- [x] Chat spec, test matrix and the MEM-203 increment.
- [ ] CI; merge; on staging, ask the questions that failed on 2026-10-01 and watch `chat.guardrail.unavailable`.
