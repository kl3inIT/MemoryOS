# A system prompt that stays the same between turns

Linear: [MEM-260](https://linear.app/memory-os/issue/MEM-260). Plan: [plan.md](plan.md). Follows
[MEM-269](../../completed/mem-269-prompt-cache/design.md).

## Problem

Providers cache the longest prefix a request shares with an earlier one. The order is tools, then the system prompt,
then the history. On staging (MEM-269 verification, 2026-10-15), a three-turn conversation sent:

- a different system prompt on every turn (three `system_fingerprint` values);
- the same system prompt on every call within a turn.

Claude rewrote about 5k tokens at the cache-write rate on each new turn. Only the tool block carried over.

The cause is the date line. `ChatPrompts.resolve` fills `{{CURRENT_DATETIME}}`, or adds Onyx's `Additional Information`
line, with `Instant.now()`, which is precise to the microsecond. Each turn resolves the instructions again, at its own
admission. The other parts of the system message change only when their source changes:

- the agent instructions;
- the language hint;
- the channel note;
- the user information and memory (MEM-250);
- the tool guidance `forInference` puts first, which depends only on the turn's tools.

## Decision

The system prompt carries the UTC day with its weekday, `Thursday 2026-10-15 (UTC)`, in both the placeholder and the
`Additional Information` line. Two turns on the same UTC day therefore send a byte-identical system message, and the
day changes it once.

- **Onyx:** writes the time to the minute.
- **pi:** first reduced its prompt to the date, then removed even that, for the same cache reason.
- **Lost information:**
  - The model no longer reads the time of day.
  - It never knew the member's time zone, so it could not turn the UTC instant into a local hour anyway.
  - Relative searches ("last week") already take their date from `SearchPrompts`, separately.

Not chosen:

- **Move the exact time into the newest user message.** That keeps the system message stable. But it adds a moving
  part to every question, including the copies the search rewrite and Deep research read, to keep a fact nothing in
  MemoryOS depends on.
- **Freeze the instant per conversation.** A conversation reopened on a later day would carry a stale date.

The system message stays one message (MEM-254: 9Router keeps only the first).

## Not in this increment

- Memory edited by `update_memory` changes the user information, and so the system prompt, from the next turn. That
  is rare and correct.
- Why luna through 9Router reads only about 17k tokens of a stable prefix (MEM-269 verification).
