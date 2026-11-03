# MEM-306 plan

- [x] Parts: line-bounded, about 15,000 characters, 10 lines of overlap; a short transcript is one part.
- [x] First reading per part with the MEM-305 rules for a part; candidates with evidence first.
- [x] Second reading of the whole with the numbered candidates: groups, left-out candidates, topics, summary, kind; never adds.
- [x] Bounds: first reading capped at 120,000 characters (both ends), overlap capped at 3,000 characters, three parts at once within five minutes.
- [x] Assembly: evidence, owner and deadline from the group's candidates; unknown, repeated and wrong-kind numbers ignored.
- [x] Tests: `TranscriptSummarizerTest` (parts, prompts, assembly, both schemas), `ChatSessionApiIntegrationTest` (two readings, two usage rows).
- [x] First measurement: decision recall fell to 0.37; see the design.
- [x] Merge-only step: no exclusion of information, every candidate kept.
- [ ] Deploy the merge-only step to staging and measure it; keep or revert.

## Status

- 2026-11-03: opened after the MEM-305 result; the owner agreed to merge the repeat and extra fix with reading in parts.
