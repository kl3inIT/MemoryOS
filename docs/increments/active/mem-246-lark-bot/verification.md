# MEM-246 verification

Evidence gathered while the increment is in flight. Each section names what it checks, how and what it found.

## Load run on staging (2026-10-15)

What it checks: plan step 6's load run that fixes the turn bounds, and step 1's check that 30 simultaneous turns
from the benchmark accounts all answer.

How: the eight benchmark accounts (`owner`, `exec`, `finance`, `legal`, `governance`, `ir`, `hr`, `outsider`) of
the staging Tenant each sent N questions at once, every question in its own new session, through
`POST /api/chat/sessions/{id}/messages`; each reply was read until it settled, and the sessions were deleted. The
question asks for one short sentence, so a turn is one model call, sometimes with one search. Container CPU and
memory were sampled with `docker stats` every few seconds. Bounds in force: the API 48 turns, a person 3, the
database pool 15; `memoryos-channels` 16 turns.

| Run | Turns | Running at once | Outcome | Seconds per turn (min / p50 / p90 / max) | Wall |
| --- | --- | --- | --- | --- | --- |
| 8 × 1 | 8 | 8 | 8 completed | 5.7 / 5.8 / 7.0 / 7.0 | 7.0 s |
| 8 × 4 | 32 | 24, 8 waiting | 32 completed | 3.5 / 4.5 / 12.2 / 35.3 | 35.3 s |
| 8 × 6 | 48 | 24, 24 waiting | 48 completed | 3.6 / 7.4 / 13.2 / 15.6 | 15.6 s |
| 8 × 12 | 96 | 24, 72 waiting | 96 completed | 2.7 / 10.7 / 16.6 / 24.9 | 24.9 s |

Found:

- Every turn completed; none failed with the capacity code, a provider rate limit or a timeout. A person's fourth
  and later questions waited for their own bound and ran in turn, the longest wait well under the one-minute
  limit.
- The provider took 24 turns at once without a rate limit or a slower answer. Its limit was not reached, so none
  is recorded beyond "above 24 simultaneous short turns".
- The host (4 cores, 16 GB) kept headroom: the API peaked at about 1.4 cores and 1.4 GiB, OpenSearch at about
  0.85 core, PostgreSQL under 0.6 core; the one-minute load average stayed under 5.7.
- The process bound of 48 was not reached: eight accounts at three each run 24 at most. Bot turns were not
  loaded, because they need as many Lark senders; they run on the same chat core and provider, and their bound
  of 16 is below the 24 measured here.
- The three slow turns of the 8 × 4 run (28–35 s) were searches, not the model. Ten hybrid searches that started
  within 0.7 s of each other all returned together 15–16 s later; OpenSearch's memory rose about 250 MB at that
  moment. It was the first search load after the cluster had been idle, and the two later runs, with more
  searches, had none. It reads as the k-NN graphs being loaded into memory on first use; the k-NN statistics
  that would confirm it need the cluster administrator, which the run did not use.

Decision: the bounds stay as designed (48 in the API, 3 a person, 16 in `memoryos-channels`, the pool 15). The run
shows they hold under this load with headroom; it gives no evidence for raising them, and the process bound
stays a backstop that a burst from more people than eight would meet before the provider does.

## The provider's limit (2026-10-15)

What it checks: the provider's rate limit that the load run above did not reach.

How: from the staging host, N short chat completions at once straight to the provider staging uses (9Router,
model `cx/gpt-6-luna`, three Codex accounts behind it), with the benchmark's key, without MemoryOS; twenty seconds
between levels.

| Simultaneous requests | Outcome | Seconds (p50 / p90 / max) |
| --- | --- | --- |
| 24 | 24 answered | 1.9 / 3.6 / 6.8 |
| 48 | 48 answered | 1.6 / 2.4 / 4.2 |
| 96 | 96 answered | 1.8 / 3.2 / 88.7 |
| 160 | 160 answered | 2.2 / 3.0 / 7.9 |
| 256 | 40 answered, 216 refused (503 carrying Codex's 429) | 1.9 / 2.3 / 3.1 |

Found:

- The limit lies between 160 and 256 requests at once. Above it Codex answers `429 Rate limit exceeded`, and 9Router
  then locks every account for the model, for 2 seconds up to 5 minutes.
- Going over the limit stops every turn on staging, not only the excess: a MemoryOS turn sent right after failed
  with `CHAT_EXECUTION_FAILED`, and turns answered again about a minute later. The same 9Router also serves
  other applications on the host, which share this limit.
- So the sum of the turn bounds of all processes that use the provider has to stay under its limit, with room
  for a turn that makes several model calls: 48 in the API and 16 in `memoryos-channels` use 64 of about 160.
