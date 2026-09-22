# MEM-123 — AI usage limits

MemoryOS records what every AI call costs ([AI usage contract](../../../specs/ai-usage.md)), and since MEM-139 a
manager can read a period back as a report. Nothing stops the spending. One person running deep research overnight,
or one runaway agent, can spend the Tenant's budget before anyone reads the report the next morning.

This increment lets a model manager cap AI spending per Tenant, per Group and per person, and refuses a chat turn
that would exceed the cap before the provider is called.

## Reference

Onyx (`backend/onyx/server/query_and_chat/token_limit.py`, `backend/onyx/db/user_usage.py`,
`backend/ee/onyx/server/token_rate_limits/api.py`, read at `D:\MemoryOS\.tmp\onyx`):

- one table, `token_rate_limit`: a token budget and/or a cost budget, a period, and a scope of
  `global` / `user` / `user_group`. A `user` limit is one budget template applied to each person separately, not a
  budget for one named person;
- the check runs **before the provider call** and only for the chat family: sending a chat message, generating a
  session name, a Craft turn and the LLM gateway. Images, voice, embeddings, indexing and the Slack bot are never
  checked;
- background work is neither counted nor blocked: Onyx captures usage only where a request carries a user;
- when the cap is reached the request is refused with HTTP 429, a `Retry-After` header, and a body naming the scope
  and when the budget resets. The browser shows a banner counting down and offers no retry;
- a reply already streaming is never cut off. There is no mid-stream check;
- session naming swallows the refusal and falls back to a default name;
- the admin screen is not a page of its own: the panel is embedded in the admin usage page, with one tab per scope.

Mobbin, for the screen: [StackAI](https://mobbin.com/screens/bdf7db95-c650-4afe-9650-a7a5f6ba0fdb) puts the limit and
the current usage on the same row; [Langdock](https://mobbin.com/screens/a05b79e4-eb8a-4178-9b3d-e48506dbc34d) warns
at thresholds before it blocks; [LangChain](https://mobbin.com/screens/4caabb4c-ea78-4eb9-a5a9-4e628a0ac074) keeps an
explicit way back to no limit.

## Decisions

**Both a token budget and a cost budget, as in Onyx.** They answer different questions, and neither covers
everything. A token budget applies to every flow; a cost budget is what a manager actually budgets in. But
[AI usage](../../../specs/ai-usage.md) states that a call whose model has no price adds tokens and one
`unknown_cost_calls`, never a zero cost, and that every cost is an estimate from catalog prices, not an invoice. So a
cost budget silently ignores an unpriced model — a local vLLM provider, and today every image and voice call. The
screen says so where a cost limit is set, and a Tenant that runs unpriced models is told a token limit is the one
that binds.

**The check reads committed usage, as in Onyx.** Turns that are still running are invisible to it, so several turns
starting at once can all pass and push the total past the cap. MemoryOS is already better placed than Onyx here,
which writes usage from a background queue about two seconds late and drops samples under pressure: MemoryOS writes
usage in the transaction that finishes the turn, so the total is exact once a turn settles. Closing the remaining gap
needs a reservation row or a locked counter on the hot path of every turn; that is a heavier mechanism than the
problem is worth at this size, and it is not what Onyx does. The overshoot is bounded by what the turns running at
that moment consume, and the next turn is refused.

**The chat family only.** Chat and deep research are where the money goes and where the choke point already exists.
Images, voice and query embeddings keep being recorded and keep appearing in reports, but are not refused. This is
Onyx's boundary too.

**System work is never counted and never refused.** `ai_usage` already carries a null actor for work no person asked
for — the indexing embeddings the Worker runs. A limit counts rows with an actor only, so indexing a large Source can
never exhaust a person's budget.

**No mid-stream cutoff.** A reply that has started is finished. The turn-level budget
(`ChatExecutionProperties`, `ChatAdmissionLedger`) already bounds a single turn; this increment bounds how many turns
a person or a Tenant may run.

**`MODELS_MANAGE`, not a new capability.** Every usage surface — costs, breakdowns, reports — is already a model
manager's. A limit is a model manager's decision about spending, and the audit trail records who changed it.

## Stream

`ai_usage_limit`, one row per configured limit and Tenant:

| Column | Meaning |
| --- | --- |
| `scope` | `TENANT`, `GROUP` or `PERSON`. `PERSON` is one budget applied to each person separately |
| `group_id` | set only for `GROUP`, and the row is deleted with the Group |
| `token_budget` | tokens in the period, or null |
| `cost_budget_usd` | estimated cost in the period, or null |
| `period_days` | the window the budget covers |
| `enabled` | a limit kept but not enforced |

At least one budget is set. Token budgets use a trailing window of whole UTC days, which is how `ai_usage` is
bucketed; a cost budget uses the same window, rather than Onyx's separate calendar-month rule, because MemoryOS
reports already speak in periods of days.

`ai_usage` gains an index on `(tenant_id, actor_id, day)`: the per-person check reads it on every turn, and today only
`(tenant_id, day)` exists.

## Refusing a turn

`ChatTurnService.sendLocked`, between resolving the model and reserving the turn: the actor, the Tenant, the model and
the flow are known, nothing is persisted, and no provider byte has moved. A limit that binds throws
`ChatException.limitExceeded()`, which the API answers as HTTP 429 with `Retry-After` and the instant the budget
frees, and the browser shows as a banner naming which budget was reached — the Tenant's, the Group's or the person's.

A person in two Groups is under the Group limits of both; the more binding one refuses, which matches how
[AI usage](../../../specs/ai-usage.md) already attributes a person's spend to every Group they belong to.

Session naming checks the same limits and swallows the refusal, as in Onyx: a session keeps its fallback name rather
than failing.

When no limit is configured, the check costs one cached lookup and no query.

## Recording the change

Every limit created, changed or deleted is recorded through [audit](../../../specs/audit.md) — MEM-25 was built for
consumers like this one. A refused turn is not an audit event: it is the system working as configured, and at chat
volume it would drown the stream. The refusals are visible as usage that did not happen, and the counter
`memoryos.usage.limit.refusals` carries them for operations.

## Screens

**Admin › Theo dõi › Chi phí AI** gains a Limits section under the existing figures, so a manager sets a cap while
looking at what is being spent. Each row shows the limit beside the current usage in the same period, the scope, a
switch, and a way to remove it. Creating one asks for the scope, the budget (tokens, cost or both) and the period.

**Settings › Usage** gains the banner MEM-98 left for this increment: what the person has used of the budget that
binds them, and when it resets.

## Scope

In: the table, the check on the chat family, the refusal contract, admin CRUD, the two screens, audit of changes.

Out: threshold warnings at 80%/100% (the next increment), limits managed by a Group manager rather than a model
manager, limits on images, voice and embeddings, reservations that close the concurrency gap, and anything to do with
billing or invoices.
