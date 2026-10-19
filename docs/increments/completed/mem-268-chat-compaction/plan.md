# Implementation plan

Design: [design.md](design.md). Each pull request is accepted when CI is green and staging deployed it.

## 1. Bulky content first

- [x] `ChatTurnSetup`: older turns replay their file metadata without the inlined file text and artifact JSON, the
  newest turn unchanged; tests on the selected messages and the token count.

## 2. The summary and how a turn uses it

- [x] Migration V171: `chat_session_summary`, and `chat_session_compaction` with the claim (`claimed_until`) and the
  cooldown (`retry_after`) one row a session; persistence in `chat.session.persistence`.
- [x] `ChatTurnSetup.resolve`: the branch's summary after the system and workspace messages, then only messages after
  its cutoff; the sliding window as the guard; a summary that leaves no room for the question ignored; Deep research
  without it.
- [x] Tests at the PostgreSQL boundary: the summary on the branch, ignored after an edit above the cutoff, ignored on
  another branch, the nearest of two, deleted with the session.

## 3. Writing the summary

- [x] Probe on three staging conversations through 9Router `cx/gpt-6-luna`: a follow-up that needs an early turn,
  answered with the summary and with the window alone; the summary's tokens against the provider's count. Two prompt
  changes came of it. Recorded in [verification.md](verification.md).
- [x] Migrations V172 (the `CHAT_COMPACTION` task for every Tenant, the usage flow `NOT VALID`) and V173 (validates
  it); the Models page row; reasoning `OFF`; `ChatCompaction` with the prompt, the trigger at 75 % of what the next
  turn replays and the 25 % kept tail cut before a USER message.
- [x] `ChatCompactions` from `ChatTurnService` after a completed ordinary answer is saved: on a virtual thread,
  claimed per session and read again under the claim; failure logged with a ten-minute cooldown; usage as its own
  flow; span and metrics.
- [x] Tests: the trigger does not fire again once a summary exists, the previous summary is rolled forward, a hidden
  question stays hidden, a failure keeps the window, two turns compact once, the claim and cooldown in PostgreSQL.

## 4. Acceptance

- [x] On staging, the web: a long conversation crosses the threshold; the next answer still knows the early turns; the
  context drops; a regenerated or edited answer above the cutoff ignores the summary
  ([verification](verification.md#step-4-acceptance-on-staging-2026-10-15)).
- [x] On staging, a Zalo group session crosses the threshold: it needs the owner's group conversation, so it moved to
  [MEM-297](https://linear.app/memory-os/issue/MEM-297) on 2026-10-19 with its steps (a small-context assistant on the
  group from the bot page, possible since MEM-273).
- [x] The summary prompt files facts the person states as established facts and summarises only the turns before the
  cutoff (two findings of the acceptance); the reminder names the last question to summarise
  ([verification](verification.md#the-summary-prompt-fix-2026-10-19)).
- [x] Chat spec and matrix, the task models spec, `verification.md`, the roadmap.
