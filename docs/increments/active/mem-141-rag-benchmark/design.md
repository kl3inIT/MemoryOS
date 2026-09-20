# MEM-141 — Retrieval and answer benchmark

## Why

Retrieval and Chat changes are accepted today by reading a few answers on staging. That cannot say whether a chunk
size, a search size, a rerank or a prompt made retrieval better or worse, and it cannot prove that a Group-scoped
document never reaches an actor who may not read it. This increment owns a repeatable benchmark that answers both.

## Scope

- A frozen corpus manifest, a question set and a runner under `tools/rag-benchmark/`.
- Retrieval measurement through `POST /api/search` and answer measurement through the Chat session API.
- Metrics: document-level recall@k and nDCG@5 (as Onyx `tests/regression/search_quality`), answer correctness by an
  LLM judge, abstention accuracy, Group leakage, latency.
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

Categories, at least ten questions each:

1. `lookup` — one fact inside one passage.
2. `multi_hop` — two or more documents must be combined.
3. `aggregate` — comparison or arithmetic across documents.
4. `temporal` — "mới nhất", "quý gần nhất": the newest document must win.
5. `abstain` — the answer is not in the corpus; the reply must decline and cite nothing.
6. `ambiguous` — the reply must ask instead of guessing.
7. `group` — the same question asked by two actors with different Group membership.

A `group` question names the authorized actor and the unauthorized one. The authorized run must cite the gold
documents; the unauthorized run must cite nothing from them. Because authority comes from Group grants, the question
set needs dedicated actors rather than real people's accounts: one in the Groups that hold the granted Sources and one
without them, provisioned through the admin surfaces.

## Metrics and gates

| Question | Measured by | Metric |
| --- | --- | --- |
| Does retrieval find the right documents? | `POST /api/search` | recall@5, recall@10, nDCG@5 |
| Does Chat answer from the right documents? | Chat reply `sources` | citation recall, citation precision |
| Is the answer right? | LLM judge over the reply and `gold_answer` | correctness rate |
| Does it decline when it should? | `abstain` questions | abstention accuracy, hallucinated-citation rate |
| Does authority hold? | `group` questions | leakage count, which must be zero |
| Is it fast enough? | reply `finished_at − created_at` | median and p90 seconds |

The search page and Chat measure different pipelines: `/api/search` is retrieval alone, while `search_knowledge` adds
query rewriting, filters and section selection. A chunk-size or index change is read from the retrieval metrics; a
prompt or tool change is read from the Chat metrics. A report that mixes them is misread.

The judge is a model run through OpenRouter at temperature 0, given the question, the gold answer and the reply, and
asked for a verdict and a reason. Judge verdicts are recorded with the run so a disputed score can be reread.

Leakage is a hard failure: any citation of a document the asking actor may not read fails the whole run, whatever the
other metrics say.

## Operation

The runner needs no server change: it authenticates each configured actor against Keycloak, reads `/api/search`,
creates a Chat session per question, polls the saved reply until it is no longer `RUNNING`, then deletes the session so
a run leaves no transcript behind. Credentials and the judge key come from the environment; the repository carries
names in `.env.example` and never values.

See [plan](plan.md) for the delivery order and the prerequisites the benchmark actors need.
