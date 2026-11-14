# MEM-304: meeting minutes benchmark on promptfoo

## Problem

MEM-300 and MEM-301 were measured with scripts written for the occasion: the transcripts were sent straight to the model with a copy of the prompt, and one model judged every item openly ("is this owner the one who does the work?"). The copy of the prompt drifts from the code, and the open judge was wrong often enough that the counts only showed a direction (MEM-301 plan, *Measurement*). Nothing tells whether a prompt, check or model change made the minutes better or worse.

## Decision

A benchmark beside the RAG one ([MEM-230](../mem-230-rag-bench-promptfoo/design.md)), on the same promptfoo container on staging, that scores minutes the real pipeline writes against an answer key a person reviewed.

### Data: 19 of the owner's meetings, never in git

The owner chose (2 November 2026) the 19 staging meetings of the MemoryOS Admin account that MEM-300 and MEM-301 measured; the four meetings of other people are left out. Two of the 19 turned out to be the same meeting recorded twice (06 and 07; 12 and the first half of 13), so 07 and 12 are dropped and 17 are scored. They are real Vietnamese meetings with real recognition and diarization errors, which is where wrong owners come from; a written meeting would score better than the product does. The repository may be public, so transcripts, the answer key and per-item results stay on the staging server under `/apps/memoryos/promptfoo/minutes/`; git holds the code, the method and aggregate numbers only.

### Copies owned by a benchmark account

Rewriting minutes replaces them, edits included, so a run must not touch the owner's meetings. Each meeting is copied once into a meeting of a dedicated account, *Minutes Benchmark*, a tenant member with no other data, so neither the owner's list nor the RAG roles change. MemoryOS has no way to create a meeting from a transcript and the benchmark is no reason to add one ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)), so `copy.py`, run on the staging host, copies the rows in one transaction: the meeting (title, kind, language, participants, terms; ended, minutes not yet written), its utterances in order with new ids, and its speakers with the names they had. A copy is recorded by its source id and never made twice. The copies' transcripts give the corpus fingerprint; a result on another fingerprint is not comparable, as in MEM-230.

### The real pipeline writes the minutes

The provider asks MemoryOS to write the copy's minutes again (`POST /api/meetings/{id}/minutes?discardEdits=true`) as the benchmark account and waits until they are `READY` or `FAILED`, as MEM-230's provider asks the real Chat. Prompt, checks (MEM-300), owner linking (MEM-301), the Tenant's `MEETING_MINUTES` model and its settings are all what users get. A change is measured after it is deployed to staging. Comparing models means changing the Tenant's model for the run; that is recorded with the result, not automated.

### The answer key

Per meeting: the decisions and the actions a careful note-taker would write, each with the transcript line it rests on, and for an action its owner — a voice of the meeting (`track`, `label`), a name the transcript gives, or none when the meeting leaves it open. Claude drafts each key from the transcript and a second, independent Claude pass reviews it against the whole transcript and settles every doubtful item; the owner asked for the model to make those calls (2 November 2026) rather than review on the local page built for it. The key therefore measures how close the minutes come to a stronger model's careful reading, not to a participant's memory. The reviewed files are frozen with a fingerprint.

### Scoring

The open judge is replaced by a narrow one: per meeting, one call to `cx/gpt-6-luna` through 9Router lines the produced items up with the key's (same decision or same piece of work, with a reason per item; repeats may match one key item), and every metric is then counted, not judged:

| Metric | Counted as |
| --- | --- |
| `action_recall`, `decision_recall` | key items matched by a produced item |
| `action_precision`, `decision_precision` | produced items that match a key item |
| `owner_accuracy` | matched actions whose owner equals the key's: the same voice, the same name compared without case and spacing, or both none |
| `owner_wrong` | matched actions given an owner other than the key's (a wrong owner is worse than none) |
| `owner_unverifiable` | matched actions whose key names a person while the minutes give a voice nobody named; left out of the two above |
| `duplicates` | produced items matched to a key item another produced item already matched |
| `quote_in_line` | produced quotes found in the line they cite |

Each meeting runs three times (`repeat`), because the model's minutes vary; a result is the mean over meetings and repeats with its spread.

## References

- MEM-230 on staging: provider calling the real API as a signed-in role, Python assertions returning named scores, the judge through 9Router, results tied to a corpus fingerprint (`tools/rag-benchmark/promptfoo/memoryos_provider.py`, `assertions.py`).
- promptfoo: Python providers and assertions, `repeat` for variance.

## Out of scope

Transcription and diarization quality, the summary text, topics, and any change to the minutes themselves; this increment only measures them.
