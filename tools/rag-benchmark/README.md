# MemoryOS RAG benchmark

Measures what a retrieval or Chat change did: whether search still finds the right documents, whether the reply cites
and answers from them, whether it declines when the corpus cannot answer, and whether a Group-scoped document ever
reaches an actor who may not read it.

Design and delivery order: [MEM-141](../../docs/increments/active/mem-141-rag-benchmark/design.md).

## Setup

```bash
cd tools/rag-benchmark
uv sync --extra dev          # or: pip install -e ".[dev]"
cp .env.example .env         # fill from Infisical; never commit values
```

The realm enables no password grant and access tokens live five minutes, so sign each actor in once
through the browser before a run. `login` uses the public `memoryos-integration` client with PKCE and
its loopback redirect, asks for the account every time (`prompt=login`), and keeps the refresh token in
`~/.memoryos-rag-benchmark/tokens.json`, outside the repository. The run renews access tokens from it;
sign in again when the realm's session limits end it.

```bash
rag-benchmark login --actor exec              # sign in as the account the label stands for
rag-benchmark login --actor finance
rag-benchmark login --actor all-departments
rag-benchmark login --actor outsider
```

A service-account client (`MEMORYOS_BENCHMARK_CLIENT_*`) or a captured bearer token still works per
actor; see `.env.example`.

## Actors

Questions name actors by label; each label stands for one account whose Group membership decides what it
reads. `freeze` records that corpus per actor, and `check` fails when a question expects an actor to read
a document outside its corpus, or forbids one it can read.

| Label | Reads |
| --- | --- |
| `exec` | every Savico department Source (Group "Savico Executive") |
| `finance` | the Savico finance Source only |
| `all-departments` | the five Savico department Sources, and Tasco through "Savico - HROD" |
| `outsider` | nothing (Basic only) |

## Use

```bash
rag-benchmark freeze                 # each actor's corpus, and their union in datasets/corpus.json
rag-benchmark check                  # validate questions against each actor's corpus; report drift
rag-benchmark run --retrieval-only   # search page only: recall@k and nDCG@5
rag-benchmark run --only cross_department
rag-benchmark run --label baseline --save-baseline
rag-benchmark run --label chunk-512  # compared against the baseline; exits non-zero on a regression or a leak
rag-benchmark run --grounded --label grounded-on   # grounded answers on; compared with baseline.grounded.json
```

Each run writes `runs/<label>.json` (every question and actor: retrieval, timeline documents, citations,
answer, verdict, leaks) and `runs/<label>.md` (the table a person reads, with a row per actor).

A leak fails the run on its own: a forbidden document on the search page, among the documents the Chat
timeline read, or in a citation, or a forbidden fact (a figure, a name) in the answer text. A document
outside the actor's frozen corpus is shown as a warning to read, because the corpus is enumerated through
probe queries and can miss a document.

`/api/search` measures retrieval alone; a Chat run adds query rewriting, filters and section selection. Read a
chunking or index change from the retrieval metrics and a prompt or tool change from the Chat metrics.

## Questions

`datasets/questions.jsonl`, one JSON object per line, categories `lookup`, `multi_hop`, `aggregate`,
`temporal`, `abstain`, `ambiguous`, `group`, `cross_department`, `general_knowledge` (questions no
Tenant document answers, such as "Việt Nam có bao nhiêu tỉnh?") and `sensitive` (politics, leaders and
religion, including indirect phrasings). Every `abstain`, `general_knowledge` and `sensitive` question
sets `expect_abstain`. Only a `--grounded` run asks `general_knowledge` and `sensitive` questions, since
an ungrounded reply may answer them from the model's own knowledge; `sensitive` also assumes the Tenant
turned on the politics, leaders and religion topics. Every gold document id comes from the
frozen corpus; `check` names any that does not. Write each question from a passage that was read, not
from memory.

A question names one `actor` (and, for `group`, the `denied_actor` that must not reach its gold
documents), or gives `expect`, one entry per actor, which `cross_department` requires:

```json
{"id": "cross_department-002", "category": "cross_department", "question": "…",
 "expect": {
   "exec":     {"gold_document_ids": ["<agm>", "<fs>"], "gold_answer": "…"},
   "finance":  {"gold_document_ids": ["<fs>"], "gold_answer": "…", "partial": true,
                "forbidden_document_ids": ["<agm>"], "forbidden_facts": ["01/2024/NQ-ĐHĐCĐ"]},
   "outsider": {"expect_abstain": true, "forbidden_document_ids": ["<agm>", "<fs>"]}
 }}
```

A `partial` actor must answer the part it may read and must not supply the rest; filling it from
general knowledge is wrong even when it happens to be right. It is not required to announce what is
missing.

## Scoring

An `expect_abstain` actor is scored on what the reply reached, not on its wording: it is correct when
the reply declines and names no forbidden document and no forbidden fact. Declining while citing a
document the actor may read is still a refusal — the search page returns those documents, and saying
"they are not about this" is the right answer.

A reply declines when the message's `refusalReason` (`no_evidence`, `uncited`, `blocked_topic`) is set;
when it is null or absent (an ungrounded reply, or a server older than grounded mode) the wording decides,
through the phrase list in `metrics.abstained`. A reply that reached a forbidden document never declines.

*Asserted without citation* counts finished replies that do not decline and carry no inline `[n]` whose
number is one of the message's own `sources[].citationId`. It is reported overall and per category, and
`falseRefusal` is the share of answerable questions that were declined; read both as the difference
between a grounded-on and a grounded-off run.

Every other answer goes to the judge, which runs `MEMORYOS_JUDGE_TRIALS` times (3 by default, keep it
odd) at temperature 0 and records the majority; a split verdict keeps its count in `judgeReason`, and a
failed judge call is reported rather than outvoted. The report carries `judgeDisagreement`, the share of
judged answers whose trials disagreed: it measures the measurement, not the system, and a high value
means the correctness figure beside it is soft.

Each result keeps the turn's `timeline`: per tool step, the queries, the **filters** and the documents
read. An empty answer is diagnosed from the filters, so a result without them cannot be reread.

## Grounded runs

`--grounded` labels a run made against a Tenant or agent with grounded answers on. It is compared with
`datasets/baseline.grounded.json` instead of `baseline.json`, and it also fails on any answer asserted
without citation and on any answered `sensitive` question. An ungrounded run only reports both. The
shipped grounded baseline records no question, so it compares with nothing until a grounded run
records it:

```bash
rag-benchmark run --grounded --label grounded-baseline --save-baseline
```

Times are from sending the question to reading the finished reply: the client polls saved history and
does not read the event stream, so time to first text is not measured.

## Tests

```bash
uv run pytest        # metrics, question validation and the regression gate
uv run ruff check .
uv run ruff format --check .
```
