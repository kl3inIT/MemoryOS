# Phase 3.3 — Onyx retrieval parity and latency

Implementation and verification on 2026-09-10, in one PR. Reference checkout: Onyx `40eb240df370688ed6eeedc1272116e7829554ac`; applicable retrieval files are unchanged from the accepted `bd89d269` baseline. The current contracts live in [Chat](../../../specs/chat.md), [Search](../../../specs/search.md), and [model options](../../../specs/chat-models.md).

## Runtime changes

- Duplicate query contributions sum; equal fusion scores follow first source rank and then first query. Same-text groups share embedding/hybrid IO while keeping their weights.
- Rewrites run once per turn. Later same-source searches use current tool/original queries; expansion is reused only on a previously unsearched source type.
- Authorized adjacent chunks merge before candidate limits. Selection sees at most three around the anchor plus permitted source/date/author metadata. Expansion reads around full section boundaries, classifies concurrently, then merges overlaps.
- Helpers use native `withoutThinking()` on the selected binding. OpenAI applies their supported minimum after its converter; GPT-5 mini uses `minimal`. Answer reasoning remains unchanged. Native attempts share a helper deadline and concurrent token/cost admission.
- Native `CompletableFuture.cancel(true)` does not interrupt provider work. The native `ExecutorAsyncer` therefore submits through a helper scope that interrupts and drains the actual native operation, including its usage recording, before terminal persistence. Native binding/retries/accounting remain in Embabel.
- V35 persists upload dates independently of pipeline dates. Nested per-origin index metadata preserves source/date pairing. Metadata readiness repairs existing projections through ordinary reconciliation and reuses unchanged vectors.
- Source/time filters apply to both hybrid branches and current authorized origin metadata. PUBLIC ACTIVE FILE remains the searchable policy. Source inference skips the single-type case; time inference runs once.
- Ordered SSE publishes actual queries/filters and selected reading ranges before expansion/final citations. Browser progress and Stop/replay use the existing transport.

The first live minimum-reasoning probe exposed a classification quality regression: a neighboring answer fact was visible to the helper but excluded by MAIN_SECTION_ONLY. The prompt now follows the reference's content-before-categories order and explicitly requires adjacent context when it alone contains the requested fact. The subsequent controlled live corpus passed neighbor facts, follow-up references, insufficient evidence, citations and document injection. Twenty helper calls across those four questions took 0.905–1.798 seconds each; these are observations, not a latency SLO.

## Measurements and their limits

Before: the existing staging audit on main `6ca5f2b` measured one SearchTool call at **24.725 s**, with sequential semantic/keyword rewrite **5.637 / 6.002 s**, selection **2.409 s**, classification **4.361 s**, and eight embedding calls totaling **5.569 s**. That SSE request lasted **39.659 s**; its TTFT was not captured. Six direct Search requests measured 651–1227 ms (mean 849 ms); nine vector-only OpenSearch probes took 5–9 ms server time. Those probes exclude embedding latency.

After: `realCorpusMeasuresNativeSearchCyclesFirstTextAndTotalThroughHttpSse` imported an authorized snapshot of the same **5 documents / 142 chunks**, preserving generation/content/vectors, into isolated OpenSearch 3.8.0. Query embeddings use the live `text-embedding-3-large` provider; native Chat uses GPT-5 mini and the actual HTTP SSE reader. Chat persistence is real PostgreSQL. Retrieval authority/generation ports use the authorized snapshot fixture; current SQL authorization is tested separately. No staging deployment or access configuration was changed.

| Question / Search call | Search duration | Rewrite/filter preparation | Retrieval through authorization/fusion | Selection | Expansion/classification | Actual query texts |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| September revenue, first call after fixture startup | 10.246 s | 2.585 s | 4.924 s | 1.232 s | 1.505 s | 7 |
| Travel policy, first call in next turn | 5.374 s | 1.660 s | 1.154 s | 1.472 s | 1.088 s | 5 |
| Travel policy, second call in that same turn | 3.081 s | 0.006 s | 0.511 s | 1.218 s | 1.346 s | 2 |

The two turns completed with sources. TTFT was **24.343 / 27.264 s** and total time **25.188 / 31.709 s**. Across the three Search calls: three embedding requests, fourteen distinct hybrid requests, two semantic rewrites, two keyword rewrites, two time inferences, and three selections/classifications. The second Search reused neither rewrite call nor its expansion query set. Stage totals overlap under concurrency and must not be added as wall-clock duration.

These are two local candidate turns against the copied staging corpus, not a paired same-host experiment or a p95 estimate. A fresh staging browser comparison stopped at HTTP 401 because its existing login had expired. The earlier baseline remains separately dated; no percentage improvement or production rollout is claimed. Model decisions between tools and final answer generation still dominate part of the total time.

## Verification status

- Focused core contracts, real OpenSearch mapping/filter/vector-reuse integration, V34→V35 migration/idempotency/source revocation, native API Search/Stop/accounting and actual SDK option serialization passed.
- Native helper timeout/drain and worker file-processing startup passed after adding the worker's explicit `SearchTimings` import. Chat declares its public connector metadata/type dependency; capability boundaries remain closed.
- Browser: 23 Chromium Chat scenarios passed, including query/filter/reading progress before citations and Stop. Web check passed 105 existing tests; the additional progress/replay test passed in the focused 25-test transport suite.
- `gradlew.bat clean check --no-daemon` passed in 11m11s. The full core test JVM uses a bounded 1 GiB heap after the default 512 MiB exhausted heap during the combined architecture/persistence corpus. Later test-only additions are checked separately; latest-head CI/review belongs to the PR receipt.
- IDE inspections included warnings for edited supported files. Large generated OpenAPI and the last large API test inspection timed out; no IDE-clean claim is made for those files. Actual compilation, generated contract checks and test execution provide their validation.

Raw corpus, helper outputs, browser capture and timing receipts stay in ignored scratch/build reports. The opt-in corpus test requires `MEMORYOS_CHAT_CORPUS_TEST=true`, `MEMORYOS_CHAT_CORPUS_FILE` pointing to the authorized snapshot, and the existing managed `SPRING_AI_OPENAI_API_KEY`; no credential or corpus is checked in.
