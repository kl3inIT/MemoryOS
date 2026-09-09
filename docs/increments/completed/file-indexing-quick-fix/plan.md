# Plan

> Completed — reconciled 2026-09-08. Integrated on main as `16340f0`; the Redis timeout and FILE control fix are shipped. The checklist below is the original verification checkpoint.

- [x] Reproduce the timeout in isolated Redis; compare 2s/2s with 5s/2s.
- [x] Fix default timeout, safe error classification, and file selector alignment.
- [x] Add idle-stream regression.
- [x] Run targeted backend and browser checks.
- [ ] Integrate the scoped change directly into main; preserve unrelated MinIO work.
- [ ] Deploy through the normal build path and verify staging.
- [x] Record verification and remaining repository-wide gates.
