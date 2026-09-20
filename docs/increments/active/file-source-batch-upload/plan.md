# FILE Source batch upload plan

1. Replace the single selected `File` state with an ordered batch, retaining per-file validation and pre-creation removal.
2. Create the Source once and serially authorize, upload, and finalize each remaining file. Keep one outstanding finalization receipt and resume exactly that file after a retry.
3. State the batch contract in `docs/specs/connector.md`; add E2E coverage proving one Source creation, one lifecycle per selected file, and no replay of finalized files after a transient failure.
4. Run formatter, lint, TypeScript check, and the focused File Source setup Playwright suite.
