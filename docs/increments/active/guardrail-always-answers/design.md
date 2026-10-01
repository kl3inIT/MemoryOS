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

**The person always gets a reply.** A guardrail may decline, with its reason, but it is never a technical error.

1. **A verdict that is easy to return.** The classifier asks for one label (`QUESTION`, `CONVERSATIONAL`,
   `BLOCKED_TOPIC:<key>`) with up to 1,024 output tokens. `GroundingClassifier.verdict` reads the last kind the
   reply names, from markdown, a JSON object or a sentence, and blocks only a topic the Tenant enabled. A reply that
   names no verdict is not guessed.
2. **A second model.** A check that fails on the `CHAT_GUARDRAIL` task model (#424), for any reason including a
   refused credential, runs once more on the conversation model. Usage is recorded against the model that ran.
3. **No verdict from either.**
   - No topic enabled: the turn is answered as a question. The check then only told conversation from questions, so
     nothing is held back, and a grounded turn keeps its citation gate.
   - A topic enabled: the turn completes with a reply that lists the restricted topics and says the question was not
     answered, `refusalReason = unchecked` (V137). The answer model does not run, so a blocked topic cannot slip
     through while the check is down. The sentence is fixed text because no model is left to write it; the topic
     list comes from the Tenant's settings.
4. A reported turn failure inside the check, such as a spent budget, still ends the turn with its own code.

`CHAT_GUARDRAIL_UNAVAILABLE` (#422, never deployed) is removed with its notice.

**Why not answer anyway when topics are enabled.** Azure OpenAI completes a request without filtering when its
filter is down. Here the Tenant enabled topics precisely so that those questions are never answered, and the check's
model failing is the case where that would silently stop holding.

## Open

- The stream does not carry `refusalReason`, so the label under a declined reply appears when the stored message is
  read. The reply's text, which states the reason, streams as usual.
- The token-budget explanation above is unverified; staging will show whether unreadable verdicts stop.
