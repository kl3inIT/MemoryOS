# MEM-304 plan

- [x] The *Minutes Benchmark* account on staging, signed in as role `minutes` in the benchmark token store.
- [x] `tools/minutes-benchmark/copy.py`: copies the 19 meetings into the account's meetings, once each; prints the fingerprint.
- [x] Answer key drafted for the 17 copies and reviewed independently by Claude, at the owner's request (2 November 2026), instead of on the local page; frozen on the server (`KEYS` a69c329e6e93: 61 decisions, 97 actions, 9 left unsure).
- [x] Provider: rewrites a copy's minutes and returns them.
- [x] Assertions: the alignment call and the counted metrics.
- [x] Config builder and a summary of a run (mean and spread per metric).
- [x] Baseline: three repeats on the current staging pipeline, numbers in `docs/tests/meeting.md`.
- [x] Docs: `tools/minutes-benchmark/README.md`, roadmap.

## Status

- 2026-11-02: opened; data source decided by the owner (19 of their meetings, server only).
- 2026-11-02: account invited and signed in as role `minutes`; 19 meetings copied (fingerprint `fbc0c2298a0c`, a second run copies nothing); provider, scoring and config run end to end on one meeting (eval-oXe-2026-11-02T14:42:12, 19 s). The drafting found that meetings 06 and 07, and 12 and the first half of 13, are the same meeting recorded twice.
- 2026-11-02: the owner agreed to drop 07 and 12 (the same meetings as 06 and the first half of 13); `copies.json` lists them under `dropped` and the config builder skips them, so 17 meetings are scored and the others keep their numbers.
- 2026-11-02: baseline run `eval-q2P-2026-11-02T15:09:26` (51 writings, 10 min 25 s). Checking it by hand found two scoring faults, fixed before the numbers were recorded: the judge missed pairs spelled differently by recognition ("path spec" and "pubspec"), now told to expect them and to give a reason per item; and a name in the key against a voice nobody named was counted wrong although it may be that person, now `owner_unverifiable`. Repeats of one meeting ran at once in a first attempt and could read each other's minutes; a file lock per meeting serialises them. Stored minutes are scored again with `regrade.py`.
