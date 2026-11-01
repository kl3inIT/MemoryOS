# Verification

Staging, release `d7f8cd1f` (MEM-260 and MEM-272 together), 2026-10-15 16:27–16:30 UTC. The owner benchmark role ran
one conversation per model, three turns each, with Linear (76 enabled tools) selected. Each line is one
`ai.model.call.completed` record.

| Call | Model | Input | Cache read | Cache write | Tools | System fingerprint |
| --- | --- | --- | --- | --- | --- | --- |
| Turn 1, first | Claude Haiku 5.5 | 6894 | 0 | 6890 | 4 | `863897805cbc` |
| Turn 1, after opening `list_teams` | Claude Haiku 5.5 | 7526 | 0 | 7384 | 5 | `863897805cbc` |
| Turn 2, first | Claude Haiku 5.5 | 6894 | **6890** | 0 | 4 | `863897805cbc` |
| Turn 2, same tool opened again | Claude Haiku 5.5 | 7531 | **7384** | 0 | 5 | `863897805cbc` |
| Turn 3, first | Claude Haiku 5.5 | 7125 | **7065** | 56 | 4 | `863897805cbc` |
| Turn 3, after opening `search_documentation` | Claude Haiku 5.5 | 7479 | 0 | 7326 | 5 | `863897805cbc` |
| Turn 3, next call | Claude Haiku 5.5 | 13972 | **7326** | 0 | 5 | `863897805cbc` |

luna (`cx/gpt-6-luna`) carried the same system fingerprint on every call, started each turn with 4 tools and about
6.3k input tokens, and read 3840 to 5888 tokens from the cache per call.

## Outcome

- The system fingerprint is the same on every call of every turn of both conversations. Before, it changed every turn
  ([MEM-269 verification](../mem-269-prompt-cache/verification.md)).
- Claude reads the whole system prompt and tool list from the cache on a new turn (6890 of 6894 tokens). Before, it
  rewrote about 5k tokens at the write rate on every turn.
