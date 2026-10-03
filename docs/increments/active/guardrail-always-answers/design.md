# A guardrail never fails a turn

Status: implemented 2026-10-01 at the owner's request.

## Problem

On staging on 2026-10-01, 34 of 106 chat turns failed, about 24 of them at the guardrail check (MEM-195 Check 1):
12 replies after the last restart did not bind to the structured verdict (`InvalidLlmReturnFormatException`, on
`ocg/deepseek-v4-flash` through 9Router), 4 calls met a provider `InternalServerException`, and the check failed
the turn closed each time. It failed ordinary questions as well as sensitive ones, and the person read an error.

The check asked for a JSON object with at most 256 output tokens and reasoning switched off. A reasoning model that
keeps reasoning can spend that budget before it writes the object; this was not confirmed from payloads, which are
never logged, so the change does not depend on it.

## Decision

**A guardrail is never a technical error, and the person is not told about one.**

1. **A verdict that is easy to return.** The classifier asks for one label (`QUESTION`, `CONVERSATIONAL`,
   `BLOCKED_TOPIC:<key>`) with up to 1,024 output tokens. `GroundingClassifier.verdict` reads the last kind the
   reply names, from markdown, a JSON object or a sentence, and blocks only a topic the Tenant enabled. A reply that
   names no verdict is not guessed.
2. **A second model.** A check that fails on the `CHAT_GUARDRAIL` task model (#424), for any reason including a
   refused credential, runs once more on the conversation model. Usage is recorded against the model that ran.
   Removed by MEM-206 the same week: no guardrail project falls back to a second classifier, and point 3 already
   gives the answer model the rules ([MEM-206](../mem-206-guardrail-conversation-context/design.md)).
3. **No verdict from either: the answer model carries the rules.** The turn is answered. Its options take the
   enabled topics as an instruction (`ChatGuardrailCheck.rulesForTheAnswerModel`), sent as a system message of every
   inference: each topic's description and the Tenant's reply, to be given word for word for a message about that
   topic, with every other message answered as usual and the rules never mentioned. This is how assistants that keep
   their rules in the system prompt behave: an ordinary question is answered, a blocked one is declined in the
   Tenant's words. With no topic enabled nothing is added.
4. A reported turn failure inside the check, such as a spent budget, still ends the turn with its own code.

`CHAT_GUARDRAIL_UNAVAILABLE` (#422, never deployed) is removed with its notice.

**What was tried first, and why it went.** The first version of this change completed such a turn with a fixed reply
that listed the restricted topics and said the question had not been answered (`refusalReason = unchecked`, V137).
The owner rejected it on the screenshot (2026-10-01): a person who asked an ordinary question was told about politics
and about the product's own failure, and still had no answer. It never merged.

**The trade-off.** In this rare case blocking depends on the answer model following its instruction rather than on a
separate check, and a reply declined that way stores no `refusalReason` and no audit record. Azure OpenAI makes the
same choice for its filter: a request completes without filtering when the filter cannot run. Blocked phrases are
matched in code and are unaffected.

## Open

- The token-budget explanation above is unverified; staging will show whether unreadable verdicts stop.
