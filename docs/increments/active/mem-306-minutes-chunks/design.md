# MEM-306: minutes read in parts, then settled as a whole

## Problem

After the prompt was written by practice ([MEM-305](../mem-305-minutes-prompt/design.md)) the minutes find 0.64 of the actions and 0.57 of the decisions on the [minutes benchmark](../../../tools/minutes-benchmark/README.md), but write more that the key does not hold (0.62 and 0.69 of their items are in it) and repeat an item more often (0.09). In one writing per meeting, 54 of 189 items matched nothing and 33 repeated another: work done during the meeting written as later work ("tách lời phút 38"), information given as decisions (five test-registration rules from one call), and one commitment said five times at five lines written five times. Some extras are real work the key missed. A string check cannot merge two sentences that say the same thing in different words, and one long read still misses a third of the actions.

## What the references do

| Reference | How |
| --- | --- |
| silent-notetaker (`crates/silent-notes/src/qwen.rs`, `bridge.py`) | Extracts tagged items from small chunks of the transcript ("a 0.6B model's recall drops on long inputs"), removes near-duplicates, then a final call reads the transcript with the extracted lists |
| Meetily (`frontend/src-tauri/src/summary/processor.rs`) | Summarises overlapping chunks "capturing all key points, decisions, action items", then a combine call keeps all important details |
| opennotetaker (`crates/note-summary/src/prompt.rs`) | Chunks on turn boundaries at 48,000 characters, never truncates, merges the chunks' lists and dedupes |
| Gemini long-context guide, OpenAI GPT-4.1 guide | Retrieving many items at once from long input is less accurate than one |

## Decision

1. **First reading, per part.** The transcript is cut on line boundaries into parts of about 15,000 characters (about 4,000 tokens), each repeating the last 10 lines of the part before. Each part is read with the MEM-305 rules for one part and returns candidate decisions and actions, each with its quote and line first.
2. **Second reading, of the whole.** One call reads the whole transcript (bounded as before) with the candidates, numbered `D1…` and `A1…`, and returns groups: the candidates that record one thing, the clearest first, and that thing said once. It leaves out a candidate that does not belong (work done during the meeting, information someone gave, an idea nobody took up) and writes the topics, the summary and the kind. It cannot add an item: every item of the minutes rests on a candidate.
3. **Evidence from the candidate.** An item takes the quote and line of its group's first candidate whose line is in the transcript, and an action the first owner and deadline a candidate of its group gives, the candidate with a changed owner or deadline being put first; numbers that name no candidate, one already placed (however written: D1, D01) or one of the other kind are ignored. `MinutesChecks` (MEM-300) and owner linking (MEM-301) then run as before.
4. Both readings share the definitions of a decision and an action, now saying that information someone gave is not a decision. Each call's usage is recorded.

5. **Bounded cost and time.** The first reading takes at most 120,000 characters, the opening and the end past that as the second reading's transcript keeps them, so at most about ten parts; a part repeats at most 10 lines and 3,000 characters of the one before, so long lines still move the reading on. Parts are read three at a time within five minutes, each call taking what is left of them, and the second reading has its four: both fit the ten minutes the minutes job holds a meeting (a background security review of the first version found the uncapped reading could outlast that hold and multiply calls).

A meeting that fits in one part takes two calls instead of one; the longest benchmark meeting (57,000 characters) takes five. Kept only if the benchmark shows fewer repeats and extras without losing recall.

Known limit: a failed attempt repeats every part on retry, at most about eleven calls per attempt. Keeping part results across attempts needs a store keyed by transcript version and waits for the benchmark result.
