# Implementation plan

Design: [design.md](design.md).

- [x] `ChatPrompts.resolve` writes the UTC day with its weekday in place of the instant.
- [x] Test: two instants of one day give the same system prompt; the next day changes it.
- [x] Spec `chat.md`, test matrix, roadmap.
- [x] Staging ([verification](verification.md)): the `system_fingerprint` of a three-turn conversation stays the same, and Claude no longer writes about
  5k tokens on each new turn.
