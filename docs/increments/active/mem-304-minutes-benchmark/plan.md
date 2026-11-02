# MEM-304 plan

- [x] The *Minutes Benchmark* account on staging, signed in as role `minutes` in the benchmark token store.
- [x] `tools/minutes-benchmark/copy.py`: copies the 19 meetings into the account's meetings, once each; prints the fingerprint.
- [ ] Answer key drafted for the 19 copies (done: 77 decisions, 127 actions, 142 marked unsure), reviewed by the owner on a local page, frozen on the server.
- [x] Provider: rewrites a copy's minutes and returns them.
- [x] Assertions: the alignment call and the counted metrics.
- [x] Config builder and a summary of a run (mean and spread per metric).
- [ ] Baseline: three repeats on the current staging pipeline, numbers in `docs/tests/meeting.md`.
- [ ] Docs: `tools/minutes-benchmark/README.md`, roadmap.

## Status

- 2026-11-02: opened; data source decided by the owner (19 of their meetings, server only).
- 2026-11-02: account invited and signed in as role `minutes`; 19 meetings copied (fingerprint `fbc0c2298a0c`, a second run copies nothing); provider, scoring and config run end to end on one meeting (eval-oXe-2026-11-02T14:42:12, 19 s). The drafting found that meetings 06 and 07, and 12 and the first half of 13, are the same meeting recorded twice.
