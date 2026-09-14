# Plan

- [x] Enforce `CHAT_READ`/`CHAT_WRITE` in Chat services; owner settings stay membership-only; HTTP deny test removing the Basic edge.
- [x] Update identity/Chat specs, verification matrices and Group capability copy that still call Chat tokens reserved.
- [x] Chat-scoped citation passage read without `SEARCH_READ`; frontend, fixture and e2e.
- [x] Align with the reference: citation passages need only membership (no `CHAT_READ`); document the ungated Chat Search tool.
- [x] Measure real-database authorization stages against hybrid/expansion on the Search path; record results in `verification.md`.
- [x] Owner decision: keep the ranked recheck; batch the per-read expansion rechecks into two `authorizedSections` calls per Search call.
- [x] Index `access_public` and `access_control_list` (`group:<id>`) on every chunk; include them in `metadata_hash` (`v2:`).
- [x] Actor tokens in `SourceSearchScope`; `public OR terms(access_control_list)` on Source and direct Search queries, allowing legacy chunks without access fields until repaired (the database recheck still applies).
- [x] `SourceAccessChanged` from Group replacement and access-type changes; ingestion enqueues an `ACCESS` operation per mapped current document; the worker updates access fields in place without hiding the document or re-embedding.
- [x] V51 allows `ACCESS` (constraint `NOT VALID`); V52 validates it and backfills `ACCESS` operations for already searchable documents; real OpenSearch and PostgreSQL tests.
- [x] CI inherited from main's IAM package split is fixed on main by #110 (IAM named interfaces, worker IAM persistence scan); this branch takes that fix.
- [x] Repository-wide `clean check`: the CI gate passed on every MEM-93 merge (#109, #112, #113, #131) and on main through #146 (run 34850839912).
- [x] Staging: Basic users do not see Drive Documents and receive 404 for Drive citations; Basic citation opening works; access refresh verified in #113.
- Moved out of scope: Google Drive per-file tokens (`google_user:`/`google_group:`/`google_domain:`) wait for the MEM-88 identity contract and are tracked there, not by this increment. [MEM-105](../../active/mem-105-source-access-modes/design.md) now derives `google_user:` and `google_domain:` tokens; `google_group:` remains unimplemented.
