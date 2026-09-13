# Plan

- [x] Enforce `CHAT_READ`/`CHAT_WRITE` in Chat services; owner settings stay membership-only; HTTP deny test removing the Basic edge.
- [x] Update identity/Chat specs, verification matrices and Group capability copy that still call Chat tokens reserved.
- [ ] Chat-scoped citation passage read requiring `CHAT_READ` instead of `SEARCH_READ`; frontend, fixture and e2e.
- [x] Measure real-database authorization stages against hybrid/expansion on the Search path; record results in `verification.md`.
- [ ] Decide with the owner whether to keep, batch or drop the per-section expansion recheck (ranked recheck measured ≈ 14 ms, expansion up to ≈ 225 ms per call).
- [ ] Index access list and shared query filter for Search, Chat retrieval and passage reads.
- [ ] Access-list update queue on Group/access-type/user-file changes, metadata-only reindex and backfill.
- [ ] Google Drive per-file tokens after the MEM-88 contract.
