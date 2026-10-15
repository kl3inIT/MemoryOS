# Implementation plan

Design: [design.md](design.md). Each pull request is accepted when CI is green and staging deployed it.

## 1. Bulky content first

- [ ] `ChatTurnSetup`: older turns replay their file metadata without the inlined file text and artifact JSON, the
  newest turn unchanged; tests on the selected messages and the token count.

## 2. The summary and how a turn uses it

- [ ] Migration (the next free version): `chat_session_summary` with its claim column and cooldown; persistence in
  `chat.persistence`.
- [ ] `ChatTurnSetup.resolve`: the branch's summary after the system and workspace messages, then only messages after
  its cutoff; the sliding window as the guard.
- [ ] Tests at the PostgreSQL boundary: the summary on the branch, ignored after an edit above the cutoff, ignored on
  another branch.

## 3. Writing the summary

- [ ] Probe, only where unsure: the prompt on two or three long staging conversations through 9Router
  `cx/gpt-6-luna`; a follow-up that needs an early turn, answered with the summary and with the window alone; the
  summary's tokens against the model's own count. Recorded in `verification.md`.
- [ ] `ModelFlow.CHAT_COMPACTION` (task model row for every Tenant, the Models page row, `OFF` reasoning), the prompt,
  the trigger at 75 % of what the next turn replays, the 25 % kept tail cut before a USER message.
- [ ] `ChatTurnService`: after the answer is saved, on a virtual thread, claimed per session; failure logged with a
  ten-minute cooldown; usage recorded; span and metrics.
- [ ] Tests: the trigger does not fire again once a summary exists, the previous summary is updated, a hidden question
  stays hidden, a failure keeps the window, two turns compact once.

## 4. Acceptance

- [ ] On staging: a long web conversation and a Zalo group session cross the threshold; the next answer still knows the
  early turns; the context ring drops; a regenerated answer above the cutoff ignores the summary.
- [ ] Chat spec and matrix, the task models spec, `verification.md`, the roadmap.
