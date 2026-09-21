# MEM-141 — Retrieval and answer benchmark

## Why

Retrieval and Chat changes are accepted today by reading a few answers on staging. That cannot say whether a chunk
size, a search size, a rerank or a prompt made retrieval better or worse, and it cannot prove that a Group-scoped
document never reaches an actor who may not read it. This increment owns a repeatable benchmark that answers both.

## Scope

- A frozen corpus manifest, a question set and a runner under `tools/rag-benchmark/`.
- Retrieval measurement through `POST /api/search` and answer measurement through the Chat session API.
- Metrics: document-level recall@k and nDCG@5, answer correctness by an LLM judge, abstention accuracy, Group leakage,
  latency. Onyx `tests/regression/search_quality` scores retrieval by top-k hit and rank and answers with RAGAS; it
  has no nDCG and no authority or abstention measure, so those are MemoryOS additions.
- A baseline file and a zero-regression gate (as OrgMemory): a candidate run is accepted only when no metric falls
  below the baseline and leakage stays zero.

Excluded: changing retrieval or Chat to make measurement possible ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)),
a hosted dashboard, and training or tuning from the results.

## Corpus

The corpus is what the benchmark actor can read, not what the database holds. On staging today that is the documents
mapped to a Source; the 13 documents that belong to a chat upload (`chat_user_file`) are excluded because knowledge
search never returns them: `DocumentSearchService` drops a document whose readable origins are empty. Duplicate chat
uploads therefore do not pollute results and must not be deleted — chat messages reference them.

`corpus.py` freezes the manifest (document id, title, media type, content generation, owning Source and Groups) by
reading the API as the benchmark actor. Every later run diffs against the frozen manifest and refuses to compare runs
across a changed corpus, so a re-index or a new upload cannot be mistaken for a retrieval improvement.

A document that is indexed with zero chunks (staging: `3_hut_2026_4_28_f46c775_hut_cbtt_vn_baocaothuongnien_2025.docx`)
is recorded in the manifest as `chunks: 0`. It can never be retrieved, so it is not a valid answer source; the manifest
makes that visible instead of leaving silent holes in the question set.

## Questions

One JSONL file, one object per question: `id`, `question` (Vietnamese, as people ask), `category`, `actor`,
`gold_document_ids`, `gold_answer`, `expect_abstain`, `as_of` and `notes`. Each question is authored from a passage
that was read, and its gold documents are ids from the frozen manifest.

Categories, at least ten questions each (`cross_department` holds three so far):

1. `lookup` — one fact inside one passage.
2. `multi_hop` — two or more documents must be combined.
3. `aggregate` — comparison or arithmetic across documents.
4. `temporal` — "mới nhất", "quý gần nhất": the newest document must win.
5. `abstain` — the answer is not in the corpus; the reply must decline and cite nothing.
6. `ambiguous` — the reply must ask instead of guessing.
7. `group` — the same question asked by two actors with different Group membership.
8. `cross_department` — the answer needs documents of two departments, and each actor has its own right answer:
   the whole answer, a partial one, or a refusal.

A `group` question names the authorized actor and the unauthorized one. The authorized run must cite the gold
documents; the unauthorized run must reach none of them and state none of their figures. A `cross_department` question
gives `expect`, one entry per actor: its gold documents and answer, `expect_abstain`, `partial`, and the documents and
facts it must not reach. A `partial` actor must answer the part it may read and say the rest is unavailable; filling
it from general knowledge is wrong even when it happens to be right, because that is exactly how a restricted fact
escapes.

Authority comes from Group grants, so the question set is written against a fixed actor matrix: an executive in a
Group attached to every department Source, one department member, a member of all departments, and an outsider with
Basic only. The owner designated existing test accounts for these roles; [corpus findings](corpus-findings.md)
records the matrix and what each actor reads. `check` compares every expectation with each actor's frozen corpus and
names a question whose expectation no longer matches the Group configuration.

## Metrics and gates

| Question | Measured by | Metric |
| --- | --- | --- |
| Does retrieval find the right documents? | `POST /api/search` | recall@5, recall@10, nDCG@5 |
| Does Chat answer from the right documents? | Chat reply `sources` | citation recall, citation precision |
| Is the answer right? | LLM judge over the reply and `gold_answer` | correctness rate |
| Does it decline when it should? | `abstain` questions | abstention accuracy, hallucinated-citation rate |
| Does authority hold? | `group` and `cross_department` questions | leakage count, which must be zero |
| Does a partial reader stay within its evidence? | `partial` expectations | partial correctness |
| Is it fast enough? | reply `finished_at − created_at` | median and p90 seconds |

The search page and Chat measure different pipelines: `/api/search` is retrieval alone, while `search_knowledge` adds
query rewriting, filters and section selection. A chunk-size or index change is read from the retrieval metrics; a
prompt or tool change is read from the Chat metrics. A report that mixes them is misread.

The judge is a model run through OpenRouter at temperature 0, given the question, the gold answer and the reply, and
asked for a verdict and a reason. It runs three times per answer and the majority is the score, because the judging
model is not deterministic in practice and a borderline answer must not swing on one call; a split verdict keeps its
count in the recorded reason, and a failed judge call is reported rather than outvoted. Judge verdicts are recorded
with the run so a disputed score can be reread.

A question that expects a refusal is not judged. It is scored on what the reply reached: correct when the reply
declines and names no forbidden document and no forbidden fact. Declining while citing a document the actor may read
is still a refusal — the search page does return those documents, and saying they are not about the question is the
right answer. The first smoke run marked two correct refusals wrong on wording alone, which is the same class of
mistake as the defect the run found in the product.

Each result keeps the turn's tool timeline: per step, the queries, the effective **filters** and the documents read.
The `temporal` defect was diagnosed from the filters of one step; a result that keeps only a step count cannot be
reread and forces the run to be repeated.

Leakage is a hard failure that fails the whole run, whatever the other metrics say. A forbidden document leaks when
it appears on any surface the actor saw: the search page, the documents the Chat timeline read (`activity`), or a
citation. A forbidden fact leaks when the answer states it without citing anything: the figures of a `group` answer, or
the names and references a `cross_department` expectation lists; figures match on their digits, whatever separators
the reply uses. Checking citations alone would miss a reply that repeats restricted figures, which is why OrgMemory's
evaluation also matches answer text against denied documents. A document outside the actor's frozen corpus is
reported as a warning, not a failure, because the corpus is enumerated through probe queries and can miss a document.

## References, and where this benchmark is biased

Three benchmarks were read before fixing this design, and each was read for how it is *built*, not for
its numbers.

| Reference | Questions | Scoring | Judge | Repeats | Authority |
| --- | --- | --- | --- | --- | --- |
| OrgMemory `evaluation/` (`demo/fixtures/public-evaluation.json`, `official_scorer.py`) | 50 fixed hand-written cases, 43 allow / 7 deny | Deterministic only: HTTP outcome, terminal event, citation set equality | Optional, off by default, diagnostic only | RAGAS runner requires `--trials >= 2` and reports mean and spread | Every case carries a user, role and department; a deny leak outranks every pass rule |
| Onyx `tests/regression/search_quality` | Hand-written `test_queries.json`, ground truth unranked | top-k accuracy at 1/3/5/10, rank statistics | RAGAS (`ResponseRelevancy`, `Faithfulness`, `FactualCorrectness`) | None | None beyond a tenant id |
| LightRAG `reproduce/` and `lightrag/evaluation/` | 125 questions generated by `gpt-4o` from a corpus summary; the RAGAS sample is three hand-written questions over the documents it indexes | Head-to-head win rate; RAGAS scores with a 0.80 reference mark | `gpt-4o-mini` | None | None |

What this benchmark takes from them: the separation OrgMemory makes between a **deterministic gate** (authority,
citations, recall thresholds) and a **stochastic judge**, its rule that a judge must run more than once, and its
treatment of a leak as outranking every other result. What it does not take: LightRAG's generated questions, which
have no gold documents and cannot test authority, and Onyx's report-only stance, which has no gate.

Where this benchmark is biased, stated so a reader does not over-read a score:

1. **Contamination is not controlled, and the corpus is public.** The Savico and Tasco documents are listed-company
   disclosures. A model may answer from memory rather than from retrieval, and none of the three references controls
   for this either. The only control here is the `outsider` actor, which reads nothing: where it answers correctly,
   the answer did not need the corpus. That control covers 12 of 90 questions today, so for the other 78 retrieval
   and memory are not separated. Growing it is cheap — the question is already written — and it is the one thing
   this benchmark can do that the references do not.
2. **The questions were written from the passages they test, by the same author who wrote the gold answers.** Wording
   drawn from a passage flatters keyword retrieval, and a single author's reading is the only check on a gold answer.
   The references share this bias (OrgMemory's cases are supplied, LightRAG's RAGAS sample is written from its own
   sample documents). Two mitigations are open: measure question/passage n-gram overlap and paraphrase what overlaps,
   and have a second reader confirm each gold answer.
3. **The category mix is an authoring decision, not a measured query distribution.** Ten per category, twenty for
   `lookup`, is a design floor; it says nothing about what people actually ask.
4. **Forbidden facts are hand-picked tokens.** Two mistakes already came from that: three names in
   `cross_department-001` that the finance statements list themselves, and a `cross_department-010` token that the
   question itself contains. Both would have marked a correct reply as a leak. `tests/test_dataset_facts.py` now
   catches the second class and a corpus-wide token check catches the first, but the objective rule is OrgMemory's:
   flag an answer line that repeats **eight or more consecutive word tokens** from a forbidden document. Adopting it
   needs a local, untracked snapshot of the forbidden documents' text, which is a privacy decision the owner has to
   make, so it is recorded as an open point rather than implemented.
5. **One run per question.** The judge now runs three times and the report carries the rate at which its trials
   disagreed, but the *system* is still asked once, so latency and any non-determinism in retrieval are unmeasured
   spread. OrgMemory's rule — never present one number as the truth — applies here too.
6. **One tenant, one language, one corpus.** Nothing here generalises beyond Vietnamese corporate disclosure.

## Operation

Access tokens live five minutes and the realm enables no password grant, so each actor signs in once through the
browser with `rag-benchmark login`: Authorization Code with S256 PKCE through the public `memoryos-integration` client
and its loopback redirect, always asking for the account so that actors never share a browser session. The refresh
token is kept outside the repository and renews access tokens while a run lasts. A service-account client or a
captured bearer token remains available per actor.

The runner needs no server change: it authenticates each configured actor against Keycloak, reads `/api/search`,
creates a Chat session per question, polls the saved reply until it is no longer `RUNNING`, then deletes the session so
a run leaves no transcript behind. Credentials and the judge key come from the environment; the repository carries
names in `.env.example` and never values.

See [plan](plan.md) for the delivery order and the prerequisites the benchmark actors need.
