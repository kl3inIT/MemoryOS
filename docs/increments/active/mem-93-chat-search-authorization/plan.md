# Plan

- [x] Enforce `CHAT_READ`/`CHAT_WRITE` in Chat services; owner settings stay membership-only; HTTP deny test removing the Basic edge.
- [x] Update identity/Chat specs, verification matrices and Group capability copy that still call Chat tokens reserved.
- [x] Chat-scoped citation passage read requiring `CHAT_READ` instead of `SEARCH_READ`; frontend, fixture and e2e.
- [x] Measure real-database authorization stages against hybrid/expansion on the Search path; record results in `verification.md`.
- [x] Owner decision: keep the ranked recheck; batch the per-read expansion rechecks into two `authorizedSections` calls per Search call.
- [x] Index `access_public` and `access_control_list` (`group:<id>`) on every chunk; include them in `metadata_hash` (`v2:`).
- [x] Actor tokens in `SourceSearchScope`; `public OR terms(access_control_list)` on Source and direct Search queries, allowing legacy chunks without access fields until repaired (the database recheck still applies).
- [x] `SourceAccessChanged` from Group replacement and access-type changes; ingestion enqueues an `ACCESS` operation per mapped current document; the worker updates access fields in place without hiding the document or re-embedding.
- [x] V51 allows `ACCESS` (constraint `NOT VALID`); V52 validates it and backfills `ACCESS` operations for already searchable documents; real OpenSearch and PostgreSQL tests.
- [x] CI inherited from main's IAM package split is fixed on main by #110 (IAM named interfaces, worker IAM persistence scan); this branch takes that fix.
- [ ] Repository-wide `clean check`.
- [ ] Google Drive per-file tokens after the MEM-88 contract.
