# MEM-300: the minutes are checked after the model answers — plan

Issue: [MEM-300](https://linear.app/memory-os/issue/MEM-300). See the [design](design.md).

## Steps

- [x] Baseline: the 23 stored staging minutes and 23 fresh runs through 9Router `cx/gpt-6-luna`, measured with the current rules.
- [x] `TranscriptSummarizer.clean` takes the numbered lines; `MinutesChecks` beside it holds the owner, quote and duplicate rules.
- [x] Owner: label, group and several-people owners become null.
- [x] Quote: found in the cited line, two lines either side, or two to three consecutive lines, nearest first; the line moves to the match; a shortened quote becomes the line's own words; otherwise the quote is dropped. A lineless item takes the line of a unique match.
- [x] Duplicates: a decision that rests on an action's sentence (one quote holds the other) or says the same on its line (overlap 0.6) is dropped; same-kind items on one line merge at overlap 0.6, anywhere at 0.8; a merged action keeps what only the repeat said, and two people's work is never merged.
- [x] `TranscriptCorrector.clean`: a proposal that would add a repeated word or run of words to its sentence is dropped.
- [x] Tests: `TranscriptSummarizerTest` (each owner case, quote on its line, one line off, across lines, shortened, missing, lineless; each duplicate case), `TranscriptCorrectorTest` (the "Google Drive Drive" case, a repeat of the word before, and words the speaker really repeated).
- [x] Measurement after: the same 23 + 23 through the new rules; cleared owners and dropped quotes listed for review.
- [x] Documents: the Minutes and correction sections of `docs/specs/meeting.md`, `docs/tests/meeting.md`, the roadmap row.
- [ ] Staging, after deployment: rerun the minutes of three real meetings and measure them.

## Verification

- `:memoryos-core:test --tests '*TranscriptSummarizerTest' --tests '*TranscriptCorrectorTest'`, on zm.
- `clean check` in CI.

## Measurement

23 meetings remain on staging after seven test and duplicate recordings were deleted on 2026-11-02, all with minutes. *Stored* are the minutes on staging; *fresh* are the same 23 transcripts sent with the current prompt to `cx/gpt-6-luna` on 9Router the same day. The measurement script mirrors `MinutesChecks`.

| | Stored before | Stored after | Fresh before | Fresh after |
|---|---|---|---|---|
| Actions | 117 | 116 | 82 | 82 |
| Owner one person | 23 | 23 | 21 | 21 |
| Owner a speaker label | 45 | 0 | 28 | 0 |
| Owner a group or several people | 18 | 0 | 6 | 0 |
| Decisions | 56 | 53 | 42 | 40 |
| Quotes kept | 173 | 163 | 124 | 122 |
| Quote lines moved / quotes shortened restored / dropped | | 8 / 1 / 6 | | 3 / 3 / 0 |
| Items on a line another item cites | 16 | 12 | 10 | 8 |

Every cleared owner was a speaker label, a group, an organisation ("đơn vị quản lý dự án", "nhà thầu thi công", "Ban nhân sự") or two people joined by "với"; no person's name was cleared. The six stored quotes dropped were rewritten by the model or stitched from a long block, not shortened. The items still sharing a line are different things said in one long line: a decision and an unrelated action, or two pieces of work (the review of #870 showed the first rule, which dropped any decision on an action's line, also dropped those).

## Status

- 2026-11-02: increment opened from the staging audit; checks implemented and measured on staging data.
