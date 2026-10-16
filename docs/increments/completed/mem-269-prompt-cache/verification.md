# Verification

Staging, release `abcaf45f`, 2026-10-15 15:11 UTC. The owner benchmark role ran one conversation per model, three
turns each, with Linear selected. That gave 67 tools on every call: 64 Linear tools at the per-turn cap plus three of
MemoryOS's own. Each line below is one `ai.model.call.completed` record.

## Claude Haiku 5.5 (Anthropic API)

| Turn | Input | Cache read | Cache write | System fingerprint |
| --- | --- | --- | --- | --- |
| 1, first call | 35474 | 0 | 35470 | `75aa5c767846` |
| 1, after `list_teams` | 35729 | 35470 | 0 | `75aa5c767846` |
| 2, first call | 35558 | 30686 | 4868 | `0ba8976c0afa` |
| 2, after `list_issues` | 38318 | 35554 | 0 | `0ba8976c0afa` |
| 3 | 35933 | 30686 | 5243 | `a7955e5294f5` |

Before MEM-269, Claude read nothing from the cache. Now 73% of its input is read from the cache (132396 of 180964).
Within a turn, every call after the first is read whole.

## luna (`cx/gpt-6-luna` through 9Router)

| Turn | Calls | Input per call | Cache read per call | System fingerprint |
| --- | --- | --- | --- | --- |
| 1 | 2 | 20247, 20431 | 0, 17152 | `fd35bbfe31cd` |
| 2 | 2 | 20290, 20608 | 17152, 17152 | `f9ce374f3184` |
| 3 | 2 | 20520, 20871 | 17152, 18176 | `f84c0b70e5ec` |

luna read 85% of its input from the cache (103936 of 122967). Over the 14 days before, the ledger showed 8.9%; most of
those turns were single-call benchmark questions, so the two figures are not a like-for-like measure of this change.

## What the fingerprints show

- The tool fingerprint (`196a1887632d`) is the same on every call of both models. The tool list no longer changes
  between turns.
- **The system prompt changes on every turn** (three fingerprints per conversation) and stays the same within a turn.
  - Claude therefore rewrites about 5k tokens at the write rate on each new turn; only the tool block (30686 tokens)
    carries over.
  - luna's read stops at about 17k tokens. The system prompt sits before the history in its prefix, so nothing after it
    is read on a later turn, and not even within a turn.
  - Moving per-turn content out of the system prompt is [MEM-260](https://linear.app/memory-os/issue/MEM-260).
- The 64-tool cap is a quarter of the input: Linear's tools cost about 30k tokens on Claude, now mostly read at the
  cache rate.
