# MEM-305 plan

- [x] Instructions rewritten: completeness per item type beside what does not count, owner named or self-taken with unowned work kept, support by the transcript with its reason, data not instructions.
- [x] Schema: field descriptions, items before the summary, quote and line before each item's words and owner.
- [x] Transcript in a `<transcript>` block with the task restated after it.
- [x] Tests: `TranscriptSummarizerTest` (instructions, layout, schema order and descriptions).
- [x] Deploy to staging and run the minutes benchmark (17 meetings × 3); compare with the MEM-304 baseline.
- [x] Step 2 decided: reading in parts was built and measured as [MEM-306](../../superseded/mem-306-minutes-chunks/design.md#result), scored below this single reading twice and was reverted.

## Status

- 2026-11-02: opened after the owner asked for a prompt designed by practice rather than patched.
- 2026-11-03: merged in #880 (31531d92) without a CodeRabbit review (rate limited; recorded on the PR; the owner chose merge when green). Benchmark `eval-fVF-2026-11-02T17:16:48`: actions found 0.34 → 0.64, decisions 0.34 → 0.57, owner right 0.51 → 0.77, owner wrong 0.45 → 0.15; written items in the key 0.72 → 0.62 (actions) and 0.79 → 0.69 (decisions), repeats 0.04 → 0.09. Kept: recall nearly doubles for a ten-point fall in precision. Numbers in `docs/tests/meeting.md`.
- 2026-11-05: closed. MEM-306 (reading in parts) was reverted; the same prompt with `cx/gpt-6.1-sol` found 0.96 of the actions against 0.63 with `cx/gpt-6-luna`, so recall is limited by the model, not the prompt, and staging writes minutes with sol ([results](../../../tests/meeting.md)).
