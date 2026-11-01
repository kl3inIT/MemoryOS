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

Decision, which replaces the load run's: the API's bound becomes 80 and `memoryos-channels`' 24, 104 of about 160,
leaving room for the other applications on the same 9Router and for a turn that calls the model in parallel. A
person's bound stays 3. The API's database pool stays 15: the pools of the API and the Worker are held to 25 by
the sizing policy, staging's role allows 40, and the 24 turns at once above ran on 15, since a turn holds a
connection only for its writes. The host was measured up to 24 turns at once; a run with more accounts checks 80.

## Restarts on staging (2026-10-15)

What it checks: plan step 1's turn cut by a restart of the API, and step 4's restart of `memoryos-channels` while
idle.

- **The API during a long turn.** A question asking for a 3,000-word essay was sent as `owner`; while it ran,
  `docker restart memoryos-api` took 7 seconds and the API was healthy 27 seconds later. The reply was already
  `FAILED` with `CHAT_INTERRUPTED` when first read after the restart, well within the three-minute lease. A next
  question in the same session, under the failed reply, was accepted (`202`) and `COMPLETED`.
- **`memoryos-channels` while idle.** The bot was `CONNECTED`; `docker restart memoryos-channels` at 06:23:06, the
  container healthy at 06:23:30, and the bot `CONNECTED` again with `connectedAt` 06:23:25, about 20 seconds after
  the restart began.

Not run: a restart of `memoryos-channels` during an answer, which needs a message sent from Lark.
