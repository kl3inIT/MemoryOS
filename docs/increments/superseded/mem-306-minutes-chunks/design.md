# MEM-306: minutes read in parts, then settled as a whole (not kept)

**Status:** measured twice on staging and reverted; the minutes run the single reading of [MEM-305](../../completed/mem-305-minutes-prompt/design.md). See [Result](#result).

## Problem

After the prompt was written by practice ([MEM-305](../../completed/mem-305-minutes-prompt/design.md)) the minutes find 0.64 of the actions and 0.57 of the decisions on the [minutes benchmark](../../../tools/minutes-benchmark/README.md), but write more that the key does not hold (0.62 and 0.69 of their items are in it) and repeat an item more often (0.09). In one writing per meeting, 54 of 189 items matched nothing and 33 repeated another: work done during the meeting written as later work ("tách lời phút 38"), information given as decisions (five test-registration rules from one call), and one commitment said five times at five lines written five times. Some extras are real work the key missed. A string check cannot merge two sentences that say the same thing in different words, and one long read still misses a third of the actions.

## What the references do

| Reference | How |
| --- | --- |
| silent-notetaker (`crates/silent-notes/src/qwen.rs`, `bridge.py`) | Extracts tagged items from small chunks of the transcript ("a 0.6B model's recall drops on long inputs"), removes near-duplicates, then a final call reads the transcript with the extracted lists |
| Meetily (`frontend/src-tauri/src/summary/processor.rs`) | Summarises overlapping chunks "capturing all key points, decisions, action items", then a combine call keeps all important details |
| opennotetaker (`crates/note-summary/src/prompt.rs`) | Chunks on turn boundaries at 48,000 characters, never truncates, merges the chunks' lists and dedupes |
| Gemini long-context guide, OpenAI GPT-4.1 guide | Retrieving many items at once from long input is less accurate than one |

## Decision

1. **First reading, per part.** The transcript is cut on line boundaries into parts of about 15,000 characters (about 4,000 tokens), each repeating the last 10 lines of the part before. Each part is read with the MEM-305 rules for one part and returns candidate decisions and actions, each with its quote and line first.
2. **Second reading, of the whole.** One call reads the whole transcript (bounded as before) with the candidates, numbered `D1…` and `A1…`, and returns groups: the candidates that record one thing, the clearest first, and that thing said once. It places every candidate in a group (a candidate no group holds becomes an item of its own) and writes the topics, the summary and the kind. It cannot add an item: every item of the minutes rests on a candidate.
3. **Evidence from the candidate.** An item takes the quote and line of its group's first candidate whose line is in the transcript, and an action the first owner and deadline a candidate of its group gives, the candidate with a changed owner or deadline being put first; numbers that name no candidate, one already placed (however written: D1, D01) or one of the other kind are ignored. `MinutesChecks` (MEM-300) and owner linking (MEM-301) then run as before.
4. Both readings share the definitions of a decision and an action. Each call's usage is recorded.

5. **Bounded cost and time.** The first reading takes at most 120,000 characters, the opening and the end past that as the second reading's transcript keeps them, so at most about ten parts; a part repeats at most 10 lines and 3,000 characters of the one before, so long lines still move the reading on. Parts are read three at a time within five minutes, each call taking what is left of them, and the second reading has its four: both fit the ten minutes the minutes job holds a meeting (a background security review of the first version found the uncapped reading could outlast that hold and multiply calls).

A meeting that fits in one part takes two calls instead of one; the longest benchmark meeting (57,000 characters) takes five. Kept only if the benchmark shows fewer repeats and extras without losing recall.

Known limit: a failed attempt repeats every part on retry, at most about eleven calls per attempt. Keeping part results across attempts needs a store keyed by transcript version and waits for the benchmark result.

## First measurement and the merge-only step

Measured on staging (eval `eval-LKq-2026-11-03T02:57:29`, 17 meetings × 3) against MEM-305: action recall 0.65 (0.64), action precision 0.57 (0.62), decision recall 0.37 (0.57), decision precision 0.62 (0.69), owner accuracy 0.73 (0.77), duplicates 0.06 (0.09), quote in line 0.97 (0.94). The writings hold about as many items as before (2.5 decisions and 8.2 actions against 2.8 and 7.7) but lose real decisions, so the leaving out is the likely cause: the rule that information someone gave is not a decision, and the second reading's licence to drop candidates.

The second step keeps the parts and the merging and removes both: the definitions no longer exclude information, the second reading is told to put every candidate in a group, and a candidate it places in no group becomes an item of its own. If decision recall comes back, leaving out is the cause; the increment is reverted if the result is still worse than MEM-305.

## Result

| Metric | MEM-305 | Parts, leaving out | Parts, merge only |
| --- | --- | --- | --- |
| Actions found | 0.64 | 0.65 | 0.73 |
| Written actions in the key | 0.62 | 0.57 | 0.52 |
| Decisions found | 0.57 | 0.37 | 0.50 |
| Written decisions in the key | 0.69 | 0.62 | 0.45 |
| Owner right, where it can be told | 0.77 | 0.73 | 0.72 |
| Repeated items | 0.09 | 0.06 | 0.08 |
| Quotes found in their line | 0.94 | 0.97 | 0.94 |

Evals `eval-LKq-2026-11-03T02:57:29` and `eval-V19-2026-11-03T03:49:44`, 17 meetings × 3, same corpus, key and scoring as MEM-305. Reading in parts finds more actions, but every variant writes more that the key does not hold and gets fewer owners right; leaving out lost real decisions, and merging without leaving out barely reduced repeats. Neither beats the single reading, so both pull requests (#882, #883) were reverted. The one gain worth keeping is that parts read in parallel finish the benchmark in 17 minutes; a later attempt at recall should check the written list against the transcript rather than read it in parts.
