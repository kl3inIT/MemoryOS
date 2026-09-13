# Verification

## Chat capability enforcement

- `ChatSessionApiIntegrationTest` (36 tests, 4 opt-in skipped) passed on the full API context with real PostgreSQL and IAM authority SQL, including `chatReadAndWriteCapabilitiesGateTranscriptAccessWhileOwnerSettingsNeedOnlyMembership`.
- `ChatPersistenceIntegrationTest` (21), `ChatTurnServiceTest` (8), `OpenApiContractTest` and core `io.memoryos.iam.*` passed.
- Web: typecheck, `src/features/groups` unit tests, i18n audit and oxfmt on changed files passed.
- Repository-wide `clean check` has not been run yet.

## Authorization cost measurement (2026-09-13)

`MEMORYOS_SEARCH_AUTHZ_MEASURE=true ./gradlew :core:test --tests io.memoryos.retrieval.SearchAuthorizationCostMeasurementTest --rerun`

Production migrations on the Testcontainers PostgreSQL (Docker Desktop, Windows host). Corpus: 50,000 documents, 200 Sources (100 PUBLIC files, 100 RESTRICTED Drive Sources each granted to two Groups), 50 Groups, actor in Basic plus 5 Groups. Warm-up 50, 300 sequential iterations per row. Candidate batches mix public, granted and denied documents.

| Operation | p50 ms | p95 ms | mean ms |
|---|---:|---:|---:|
| Calibration `SELECT 1` round trip | 1.44 | 2.21 | 1.48 |
| Capability check (`SEARCH_READ` / `CHAT_READ`) | 3.54 | 4.84 | 3.59 |
| Active Tenant lookup | 2.57 | 3.85 | 2.60 |
| PREFETCH readable Source scope | 5.69 | 7.43 | 5.78 |
| AUTHORIZATION Chat recheck, 100 candidate documents | 8.05 | 10.21 | 8.05 |
| AUTHORIZATION Chat recheck, 400 candidate documents | 13.50 | 17.32 | 13.88 |
| Direct Search recheck, 100 candidate documents | 5.56 | 8.27 | 5.99 |
| EXPANSION recheck, one passage window | 4.39 | 6.29 | 4.53 |

A first run without the calibration row gave the same order (400-candidate recheck p50 20.02 ms, p95 44.42 ms). Each query includes roughly 1.4 ms of container round trip that a same-network production database would mostly not pay.

### Per-call estimate

- Direct Search: capability + Tenant lookups + one 100-document recheck ≈ 15–20 ms against recorded direct Search requests of 651–1227 ms (≈ 2%).
- Chat SearchTool call: capability, PREFETCH, Tenant lookups and one ranked recheck ≈ 25–35 ms. Expansion then rechecks each selected section about five times (selection expand, neighbor windows, final expand), up to 10 sections: ≈ 45 ms per 10 serial rechecks, part of it parallel. Total ≈ 120–250 ms against recorded SearchTool calls of 3.1–10.2 s (≈ 2–5%).
- Hybrid OpenSearch time is from the earlier receipts in [MEM-11 latency verification](../../completed/mem-11-production-chat/latency-verification.md), not from this run.

### Decision input

The post-query ranked recheck is small (≈ 14 ms for 400 candidates) and is not the latency driver; provider rewrites, selection, classification and embeddings dominate. The only material authorization share is the repeated per-section expansion recheck. Following the reference design would remove the per-result and expansion rechecks and rely on the index filter plus sync lag; the measured saving is at most a few percent of a Search call.

Accepted: the ranked recheck stays and the per-read expansion rechecks become two batched `authorizedSections` calls per Search call (after selection, before evidence). Each batch is the same query shape measured above for 100 candidate documents (≈ 8 ms), so a call now spends ≈ 2 × 8 ms instead of up to ≈ 225 ms. `DocumentSearchServiceTest` (25), `SearchToolTest` (23) and `OpenSearchRetrievalIntegrationTest` passed after the change.

## Merged main and staging (2026-09-13/14)

- PR #109 merged as `44c43d6` after green CI. `gradlew.bat clean check` on merged main passed in 12m35s: core 500 (1 skipped), connector 108 (4 skipped), api 145 (4 skipped), worker 27; no failures.
- Staging deployed `44c43d6`, then `f5895ca` (#111). Flyway V50–V52 succeeded at 17:15 UTC; the web bundle contains `/api/chat/documents/{documentId}`.

### Defect found on staging: access refresh used scroll-dependent by-query APIs

All 14 V52 `ACCESS` operations failed after three attempts. OpenSearch logged `no permissions for [indices:data/read/scroll]` for `memoryos-service`. `_update_by_query` continues beyond its first 1,000-document batch with scroll, and the staging Drive documents have 1,150–1,338 chunks; the managed role grants bulk but not scroll. Local integration tests run OpenSearch without the Security plugin, so they could not observe it. No result was lost: projection repair re-indexed the drifting documents, but it marked them pending, hiding three Drive documents until the full rewrites completed.

A related logging defect hid the failure: the worker MDC carries `operation_id` while coordinator logs also added it as a key-value, so the staging logstash console appender rejected those events (`The name 'operation_id' has already been written`).

### Staging Group access test (direct database/index verification)

No staging login accounts were available, so the grant was applied with the same SQL as the application path: ordinary Group "MEM-93 test group" (`305260ae…`) containing member `0369ffb2`, granted to the RESTRICTED Drive Source "Việt Test Drive" (6 documents), plus the `ACCESS` enqueue statement.

| Check | Result |
|---|---|
| PostgreSQL readable Drive documents: member in the Group / member outside / owner | 6 / 0 / 0 |
| OpenSearch chunks of those documents after repair | 7,327 of 7,327 with `access_public=false`, `access_control_list=[group:305260ae…]`, none missing access fields |
| Index filter count with reader tokens: the Group / another Group / none | 7,327 / 0 / 0 |
| `ACCESS` operations for the grant | FAILED (scroll permission), repaired through full re-index |

### After the fix (#112, `5a5e036`, deployed 2026-09-13 20:41 UTC)

Access refresh and delete use chunk IDs with bulk requests; access-only drift is repaired without hiding; delivery logs no longer repeat MDC keys. The same Source was exercised in both directions with the application's SQL (grant change plus `ACCESS` enqueue):

| Step | `ACCESS` operations | Hidden documents during refresh | Chunks matched: Group token / no token |
|---|---|---|---|
| Revoke the Group from "Việt Test Drive" | 6 SUCCESS on the first attempt (about 45 s) | 0 of 6 | 0 / 0 of 7,327 |
| Grant it again | 6 SUCCESS on the first attempt (about 60 s) | 0 of 6 | 7,327 / 0 of 7,327 |

Since the deploy the worker logged no `search.index.failed` and no duplicate-name appender error, and OpenSearch logged no permission denial. The test Group remains granted on staging for manual acceptance.

Browser/API acceptance with real accounts remains open.
