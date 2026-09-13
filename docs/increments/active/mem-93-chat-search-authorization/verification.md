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
