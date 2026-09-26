# MEM-141 — staging corpus and authority state, 2026-09-21

Read from the staging database on `zm` inside read-only transactions. Nothing on staging was changed by
the benchmark work; the Source and Group changes below were made by the owner through the application.

## Sources and Groups

| Source | Access | Groups | Searchable documents |
| --- | --- | --- | --- |
| Tài liệu Savico - Tài chính | `PRIVATE` | Savico Executive, Savico - Tài chính | 5 |
| Tài liệu Savico - Pháp chế | `PRIVATE` | Savico Executive, Savico - Pháp chế | 2 |
| Tài liệu Savico - HROD | `PRIVATE` | Savico Executive, Savico - HROD | 1 |
| Tài liệu Savico - Quản trị doanh nghiệp | `PRIVATE` | Savico Executive, Savico - Quản trị doanh nghiệp | 2 |
| Tài liệu Savico - Quan hệ đầu tư | `PRIVATE` | Savico Executive, Savico - Quan hệ đầu tư | 1 |
| Tài liệu Tasco | `PRIVATE` | Savico - HROD | 46 |

The five Savico Sources moved from `SYNC` to `PRIVATE` on 2026-09-21. Under `SYNC` their department
Groups granted nothing: all eleven files carried an active Google Drive `anyone` permission, which
`JdbcSourceDocumentRepository` maps to the `everyone` token, so every active member read every file.
The eleven `ACCESS` index operations for those documents completed at 04:35 UTC, so the Search projection
carries the new access fields.

Tasco is still attached only to `Savico - HROD`. Whoever is in that Group reads the 46 Tasco documents.
The question set relies on this (`cross_department-003`); if the attachment changes, `rag-benchmark check`
reports the questions whose expectations no longer hold.

## Actors

The owner designated existing test accounts as benchmark actors. Their emails stay out of the repository;
the labels below are what `MEMORYOS_BENCHMARK_ACTORS` and the question set use.

| Label | Group membership | Readable documents (the code's read rule, evaluated in SQL) |
| --- | --- | --- |
| `exec` | Savico Executive | 11: every Savico document |
| `finance` | Savico - Tài chính | 5: the finance documents |
| `all-departments` | the five Savico department Groups | 57: every Savico document and the 46 Tasco documents |
| `outsider` | Basic only (`rag-benchmark@memoryos.test`) | 0 |

`exec` is not a superset of `all-departments`: it cannot read Tasco. There is no single-department
actor outside finance, so a partial answer is measured from the finance side only.

Every expectation in the question set was checked against these readable sets before this was written:
each actor's gold documents are readable by it and none of its forbidden documents are. `rag-benchmark
check` repeats that check through the API once the actors are signed in and `freeze` has run.

## Authentication

The `memoryos` realm issues access tokens for 300 seconds, with a 30-minute SSO idle and 10-hour maximum
session, and enables no password grant. The public client `memoryos-integration` allows Authorization
Code with S256 PKCE to `http://127.0.0.1:8765/callback` and `http://localhost:8765/callback`, which is
what `rag-benchmark login` uses. A run renews access tokens from the stored refresh token, so it outlives
the 300-second token; an actor must sign in again only after the session limits end its session.

## Question set

90 questions, 131 actor asks (2026-09-21):

| Category | Questions | Asked as |
| --- | --- | --- |
| `lookup` | 20 | `exec` |
| `multi_hop`, `aggregate`, `temporal`, `abstain`, `ambiguous` | 10 each | `exec` |
| `group` | 10 | `exec`; denied: `outsider` for the four finance questions, `finance` for the six from other departments |
| `cross_department` | 10 | every actor named, each with its own expectation |

- `cross_department-001` — HROD appointment notice × Legal charter clause 24.3.2. `finance` and
  `outsider` must decline, including the part a model knows from company law.
- `cross_department-002` — Investor-relations AGM 2024 resolution × the audited 2025 consolidated
  statements. `finance` may answer the 2025 auditor only and must say the 2023 part is unavailable.
- `cross_department-003` — Tasco's proposed 2026 auditors. Only `all-departments` may answer; `exec`
  must decline. The five firms span the boundary of chunks 0 and 1, and PwC and KPMG belong only to the
  Option 2 appendix, so the question also tests context expansion.
- `cross_department-004` … `-010` (2026-09-21) pair the remaining boundaries: governance × legal (board
  meetings held against the charter minimum), governance × finance (2024 plan attainment against 2025
  profit), investor relations × legal (2024 board election against the internal governance rules),
  investor relations × governance (2024 dismissals against the resolutions issued), legal × finance (the
  35% authority threshold converted with total assets), investor relations × finance (the 2023 profit
  distribution proposal against 2025 profit) and HR × legal (the appointment notice against the
  definition of a non-executive director).

Every forbidden fact was checked against the whole corpus before use: the token must appear in the
forbidden document and nowhere the denied actor can read. That check removed three names from
`cross_department-001`, which the finance statements list themselves, and ruled out the 2024 figures
shared by the supervisory-board report and the financial statements.

The six non-finance `group` questions now use `finance` as the denied actor, which turns them into
cross-department leak tests rather than outsider tests.

## Refresh, 2026-09-25

Staging changed under the question set between 2026-09-23 and 2026-09-24, and the 2026-09-24 baseline
(`emb-openai-3large-baseline`) errored on all 131 asks, so it is not a baseline:

- The five Savico finance documents were deleted and uploaded again on 2026-09-23 with new document ids.
  The titles are unchanged; the question set now names the new ids (58 references across gold and
  forbidden lists).
- Group `Savico Executive` was deleted on 2026-09-24, leaving `exec` with Basic only. It was recreated
  through the administration API with `bm-exec` as its only member and attached beside the department
  Group of each of the five Savico Sources, which restores the actor matrix above.
- The Tasco Source and Group `Benchmark - Tasco` were deleted, and with them both `TTr-03` documents.
  `cross_department-003` is removed and the `tasco` actor leaves `MEMORYOS_BENCHMARK_ACTORS`; the
  category holds nine questions.
- 55 documents (966 chunks) of the deleted Sources remain in `documents` without a Source mapping. No
  actor reads them, so they do not affect the benchmark.

The staging disk reached the OpenSearch flood-stage watermark on 2026-09-24, which set
`read-only-allow-delete` on the chunk index. Every index write failed from then on, including the
`ACCESS` refreshes queued by the Group change, and the write alias that `ensureIndex` adds could not be
created. Removing superseded release images freed 46 GB; OpenSearch lifted the block, and saving the same
Source Groups again queued the refreshes, which completed.

After the refresh `check` reports 89 valid questions and no drift. The retrieval-only baseline
`openai-3large-retrieval-0925` (127 asks, `text-embedding-3-large`) scores recall@5 0.977, recall@10 1.0
and nDCG@5 0.842, with no leak and no error. With eleven readable documents, document-level recall is
close to its ceiling, so it separates embedding models poorly; a model comparison needs passage-level
gold or a larger corpus.

## Open points

- `datasets/questions.jsonl` is tracked in Git and carries Tenant figures in `gold_answer`. The Savico
  and Tasco documents are listed-company disclosures, but whether Tenant content belongs in Git is
  still the owner's decision; per-actor corpus files (`datasets/corpus.<label>.json`) carry titles and
  fall under the same decision.
- `multi_hop-005` records a real inconsistency in the corpus: consolidated 2025 net revenue is
  27.778.973.716.716 in the audited statement and 27.809.694 million in the variance explanation.
- The OpenRouter judge key must be rotated before a run: it was pasted into a chat transcript.
