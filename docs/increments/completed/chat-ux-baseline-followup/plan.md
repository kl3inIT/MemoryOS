# Plan

1. [x] Compact shared picker and one-row composer; Project Files surface; header and Vietnamese copy.
2. [x] Add bounded automatic titles with fallback and manual-rename race protection.
3. [x] Carry file citation positions through server evidence and browser readers.
4. [x] Focused behavioral tests, browser layout verification, static checks and repository gate. Consolidate contracts and record verification without claiming live acceptance. See [verification](verification.md).

5. [x] Address the captured CodeRabbit grapheme-boundary finding using native `Intl.Segmenter`, including the API length ceiling and regression tests.
6. [ ] With the user's follow-up authorization, publish the fix, merge the verified exact head and deploy staging through the existing pipeline. Record exact-SHA health evidence separately from user-owned business acceptance.

No OCR edits or worktree creation. Publication/deployment evidence is separate from local implementation; post-merge evidence belongs on the linked issue, not a documentation-only PR.
