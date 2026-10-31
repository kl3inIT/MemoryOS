# MemoryOS RAG benchmark: method

This document describes, end to end, how MemoryOS's retrieval-augmented Chat is measured: what is measured and why,
the environment and tools, how the corpus and the questions were built, how a run is made, how every metric is
computed (with a worked example), how uncertainty is reported, how one step of the pipeline is switched off to
measure it, and what the method cannot show. Commands are in the [README](README.md); the current results are in
[docs/tests/chat.md](../../docs/tests/chat.md#rag-stage-metrics-mem-230-p9--2026-10-31); the decisions and their
history are in [MEM-230](../../docs/increments/active/mem-230-rag-bench-promptfoo/design.md).

## 1. What the benchmark answers

| Question | Where it is answered |
| --- | --- |
| Is a reply correct, supported by what it cites, and does it decline when the documents cannot answer? | Answer checks (section 7.4) |
| Does a role ever see a document it may not read? | Leakage (section 7.4) |
| When a reply is wrong, which stage lost the evidence: text extraction, search, the documents given to the model, or the answer itself? | Stage metrics (sections 7.1 to 7.3) |
| Does each step of the RAG flow earn its place? | Step ablation (section 9) |
| What does a turn cost in time and tokens? | Operation (section 7.5) |

A score is only comparable with another score measured on the same corpus fingerprint, the same question set and
the same judge (section 4.4). Section 1.1 explains every term for a reader new to RAG evaluation; section 13 explains
why each metric and method was chosen and which alternatives were rejected.

### 1.1 Terms

| Term | Meaning | Example |
| --- | --- | --- |
| RAG (retrieval-augmented generation) | The model first searches the company's documents, then writes its answer from the passages it found, citing them | "What was Q1/2026 revenue?" → search finds the Q1/2026 statement → the answer quotes its figure and cites it as [1] |
| Corpus | The fixed set of documents the benchmark searches | The 61 public disclosures of section 3 |
| Chunk | A piece of a document, at most 768 tokens, that is indexed and searched on its own | One table of a balance sheet, or two paragraphs of a resolution |
| Token | The unit a model counts text in, roughly three quarters of an English word; Vietnamese takes more | "Báo cáo tài chính" is about 6 tokens |
| OCR (optical character recognition) | Reading the text of a scanned page from its image | A signed resolution scanned to PDF has no text until OCR reads it |
| Keyword search (BM25) | Ranks chunks by how often the question's words occur in them, weighting rare words more | "thù lao HĐQT" finds chunks containing those words, even with no understanding of meaning |
| Embedding, vector search | A model turns a text into a list of numbers (a vector) so that texts with similar meaning are close; search returns the closest chunks | "lương của ban lãnh đạo" can find a passage about "thù lao Hội đồng quản trị" with no word in common |
| Hybrid search, score fusion, RRF | Each query runs both searches; OpenSearch scales each side's scores to 0..1 (min-max) and averages them with weights 0.5 and 0.5. When the rewrite produces several queries (a semantic one and a keyword one), their ranked lists are merged by Reciprocal Rank Fusion: each chunk scores weight/(50 + rank) in each list and the scores are added | A chunk with keyword score 1.0 and vector score 0.6 (after scaling) gets 0.8; a chunk ranked 1st in one query's list and 5th in the other's beats one ranked 2nd in a single list |
| Gold answer, gold document, evidence quote | The answer a correct reply must give, the documents that contain it, and the exact sentences it rests on, written before the system was asked | Gold: "350.000.000 đồng"; gold document `svc-bctn-2025`; quote: the table row on page 65 |
| Role | A kind of user with fixed reading rights | `finance` reads only the finance Source and the noise |
| Leakage | A reply that read or cited a document its user may not read | `hr` asks about a financial statement and the reply cites it |
| Near miss, tempting document | A question whose fact exists only for another period or company; that other document is the tempting one | Asking for Q3/2026 profit when only Q2/2026 exists: citing Q2 is wrong |
| Rank, top 10 | The position of a result in the search list; the top 10 are the first ten | The gold document at rank 3 is in the top 10 |
| Recall, precision | Recall: how much of what should be found was found. Precision: how much of what was found is relevant | 2 gold documents, 1 found → recall 50%; 4 documents read, 1 gold → precision 25% |
| LLM as a judge | A language model grades a reply against a written rule (a rubric) and the gold answer, and returns pass or fail with a reason. It is used where code cannot decide, such as whether two differently worded answers say the same thing | Gold "350 triệu đồng", reply "0,35 tỷ đồng": no string match, but a judge sees they agree |
| Rubric | The written rule a judge applies | "Pass when every point the question asks for is answered with the gold value" |
| Deterministic metric | Computed by code, always the same result for the same input; no model involved | Leakage, recall@10 |
| Bootstrap, 95% interval | Re-draw the questions at random many times and recompute the score; the middle 95% of those scores is the range the true score probably lies in | 82.4% [75.6, 88.6]: with another set of similar questions the score would most likely fall in that range |
| Paired difference | Two runs compared on the same questions, question by question, so the difference of the questions themselves cancels out | Hybrid minus keyword on the same 97 questions |
| Ablation | Remove one step, run again, and compare; the loss shows what the step contributes | Turn off the query rewrite: rows passing fall 2.4 points |
| Cohen's kappa | Agreement between two judges after removing the agreement expected by chance; 1 is perfect, 0 is chance | Two judges who both pass 90% of rows agree on 82% by chance alone; kappa counts only agreement beyond that |
| dev / test split | Questions used while improving the system (dev) and questions kept aside to decide (test) | A prompt is tuned on dev; whether it is kept is read from test |
| promptfoo | An open-source tool that runs every test case against the system, applies the checks, stores the results and shows them in a web viewer | One eval = 375 rows, each with its checks and the judge's reasons |
| Provider, assertion, eval | promptfoo's names for the adapter that calls the system, one check on a reply, and one complete run | `memoryos_provider.py`, `leakage`, `eval-C2F-2026-10-31` |

## 2. Environment and tools

Everything runs on the staging server. No customer data is used: the corpus is public disclosures in Sources of
their own, and staging data is demo data.

### 2.1 Hardware and services

| Item | Value |
| --- | --- |
| Server | Staging host `zm`: AMD EPYC 9354P, 4 vCPU, 15 GiB RAM, Ubuntu 24.04.4 |
| MemoryOS API | The released image of `main`: `0782687f` for the 2026-10-08 baseline, `dcdea3c5` for the 2026-10-31 runs; probe images for single-side search and step ablation (section 9) |
| Search index | OpenSearch 3.8.0: BM25 keyword search and k-NN vector search in one hybrid query (min-max normalised, weights 0.5 / 0.5); several queries of one turn merged by Reciprocal Rank Fusion (RRF, constant 50) in MemoryOS |
| Database and queue | PostgreSQL 18.4 (documents, chunks, access, messages and usage); Redis 8.10.2 (Chat event streams) |
| Text extraction | docling-serve-cpu v1.34.0 for PDF and Office files, Apache Tika as the fallback |
| OCR for scanned pages | PaddleOCR-VL on the GPU serving node, reached as an internal endpoint |
| Chunking | Convention `structured-cl100k-768-v4`: chunks of at most 768 tokens of the cl100k tokenizer, cut along the document's structure |
| Embedding | `Qwen/Qwen3-Embedding-4B`, 2,560 dimensions; a vector hit needs a semantic score of at least 0.7 |
| Chat model | `cx/gpt-6-luna` through 9Router (the user's model router) |
| Guardrail model | `openrouter/typesafe/jev-1.13` |

Versions of OpenSearch, docling-serve, PostgreSQL and Redis were read on 2026-10-31.

### 2.2 Benchmark tools

| Tool | Role | Version |
| --- | --- | --- |
| [promptfoo](https://www.promptfoo.dev/) | Open-source evaluation runner (MIT licence, self-hosted). It sends every test to a provider, runs the assertions on the reply, stores the result and shows it in a web viewer | 0.123.1 for the 2026-10-08 baseline; 0.125.1 on 2026-10-31 |
| `memoryos_provider.py` | promptfoo provider: signs in as the test's role, asks Chat in a fresh conversation, reads the event stream and returns the answer, its sources, the documents the model read and the turn time | this repository |
| `retrieval_provider.py` | promptfoo provider for the retrieval stage: one `POST /api/search` as `exec` to depth 50, plus the indexed text of the gold documents | this repository |
| `assertions.py` | promptfoo `python` assertions for everything a judge should not decide: leakage, tempting citations, citation numbers, context and retrieval metrics | this repository |
| `summary.py` | Rates, 95% bootstrap intervals, paired differences, strata, judge agreement and Cohen's kappa from promptfoo exports | this repository |
| Judges | promptfoo's `factuality` and `llm-rubric` assertions, graded by `cx/gpt-6-luna` (reference judge) and `cx/gpt-6-sol` (second judge) through 9Router | |
| 9Router | Routes model calls to two Codex accounts | 0.5.95 on 2026-10-08; 0.5.112 from 2026-10-31 07:25 UTC |
| Python | Python 3.14 in the promptfoo image; the client uses the standard library only | |

promptfoo runs as the `memoryos-promptfoo` container on staging. Its viewer is reached only through OAuth2 Proxy with
the `memoryos-inspector` role; the container has a read-only root filesystem, no capabilities and 1 GiB of memory.
The judge key is a 9Router key used by nothing else.

### 2.3 Run settings

| Setting | Value | Why |
| --- | --- | --- |
| Concurrency | 2 turns at a time | Chat and the judge share two Codex accounts; 3 or more lock both |
| Cache | Off (`--no-cache`) | Every turn is a real turn |
| Conversation | A new session per test | No earlier message changes the answer |
| Retries | A turn that fails with `CHAT_EXECUTION_FAILED` waits 35, 70, 300 and 600 s and asks again; rows still failing are asked once more in a second eval and merged | A router lock is not a system answer |
| Corpus check | The provider refuses every turn unless all 61 documents are uploaded and searchable | A run on an incomplete corpus grades missing evidence, not retrieval |

## 3. Corpus

### 3.1 Documents

61 public disclosures, downloaded from their publishers and verified by SHA-256, never stored in Git
([manifest](corpus/manifest.json)):

| Company | Use | Documents | Text layer: text / mixed / scanned |
| --- | --- | --- | --- |
| Savico (SVC) | Questions are asked about it | 49 | 23 / 8 / 18 |
| Haxaco (HAX) | Noise: another listed company's disclosures, never asked about | 12 | 2 / 2 / 8 |
| Total | | 61 (60 PDF, 1 Word), 429 MB | 25 / 10 / 26 |

"Text" means every page has a text layer, "scanned" means no page has one (OCR reads it), "mixed" means some pages of
each. The Savico documents cover five departments: finance 16 (quarterly, semi-annual and audited statements),
governance 17 (AGM resolutions and minutes, governance reports), HR 16 (personnel notices), legal 7 (charters,
regulations) and investor relations 5 (annual reports). Same-kind documents of other periods are kept on purpose
(six quarters, three AGM years, three charter amendments), and so are real conflicts (a superseded charter), so a
system that takes the wrong period or company is caught.

### 3.2 Access layout

Each department's documents go to their own Source, read by that department's Group and by the executive Group;
the Haxaco noise is read by every benchmark Group ([layout](corpus/layout.json)).

| Role | Reads |
| --- | --- |
| `exec` | Every benchmark Source |
| `finance`, `legal`, `governance`, `ir`, `hr` | Its own department's Source and the noise |
| `outsider` | No benchmark Source |

A role is whichever account signed in for it; no account is named in the repository. Grounded runs ask through the
agent `Benchmark - grounded` (the built-in assistant's settings with grounded answers on), so the Tenant's own
settings stay as staging users have them.

### 3.3 Provisioning and fingerprint

`python -m memoryos_bench provision` creates the Sources and Groups through the public API, uploads each file,
waits for indexing and places each role's account; it is idempotent. It prints the corpus fingerprint: a hash of
every document's content hash, the role-to-Group mapping and the question-set version. Every run records it;
all runs here are on `6f95c22c5f147a927312b04de4546dca187b2687032162e97efe4b0305ca91ee`.

## 4. Questions

### 4.1 How they were written

| Step | What happens | Bias it removes |
| --- | --- | --- |
| 1 Pages | `authoring/pages.py` extracts all 1,750 pages from the original files: 462 prose and 213 table pages from the text layer, 1,075 read as page images | Questions written from MemoryOS's own chunks, which would hide extraction and chunking errors |
| 2 Sample | `sample.json` draws 24 pages per department with seed 230 before anyone reads them, one page of every question document first | Picking easy or salient facts |
| 3 Draft | Six Claude agents, one per department and one for cross-department questions, follow [BRIEF.md](authoring/BRIEF.md): fixed category quotas, user voice (abbreviations, no diacritics in some, a few typos), verbatim quotes that stand alone | A set that flatters keyword search or always-answering |
| 4 Blind validation | `cx/gpt-6-luna` and `cx/gpt-6-sol` each answer from the question and quotes only, then from the whole document without the quote; both must reach the gold answer. A rejected question is rewritten once, then dropped | Ambiguous questions; one model judging its own phrasing |
| 5 Freeze | `authoring/finalize.py` checks every text-layer quote against its page, measures copied phrasing, derives per-role expectations from the layout and splits dev/test | Hand-typed access expectations; tuning to the questions |
| 6 Review | A seeded 20% sample (24 questions) in [review.md](corpus/review.md), read on 2026-10-06 before the freeze (the owner delegated it to Claude; the two validators stay the independent check). No gold answer was wrong; two expectation rules were fixed for the whole set | Errors nobody looked at |

Of 125 drafts (17 per department, 40 across departments and in the ambiguous, general-knowledge and sensitive groups), 111 passed validation at first, 12 were sent back once and 5 still rejected were dropped: 120 were kept. Pages with identity numbers or relatives were left out. The drafting model is neither the chat model nor a judge. Copied phrasing (share of the
question's word trigrams found in its quotes) is 0.06 on average and at most 0.41, under the 0.5 limit.

### 4.2 Categories

| Category | Questions | Example of what it tests |
| --- | --- | --- |
| Lookup | 30 | One fact from one passage |
| Temporal | 10 | The right quarter or year among several documents of the same kind |
| Aggregate | 10 | A sum, a difference or a growth rate over several figures |
| Multi-hop | 10 | Two passages of one department |
| Cross-department | 13 | Two departments' documents (finance and HR, for example) |
| Near miss | 12 | The fact exists only for another period or for Haxaco: the system must not answer with that document |
| Absent | 9 | Stated nowhere in the corpus: the system must decline |
| False premise | 6 | The question assumes something the documents contradict: the system must correct it |
| Ambiguous | 6 | Needs a period or version: the system must ask or name the choice |
| General knowledge | 8 | Grounded mode only: must not answer from outside the documents |
| Sensitive | 6 | Grounded mode only: must decline |

Passage types: 38 prose, 28 table, 33 prose on a scanned page, 19 table on a scanned page, 2 lists. Split: 75 dev,
45 test (70/30, stratified by department and category, seed 230); decisions are read from `test`.

### 4.3 Who asks, and what each role must do

A department question is asked by its department's role, by `exec`, and by one other department's role chosen from
the question id. Cross-department, near-miss and false-premise questions of the cross set are asked by all seven
roles. Ambiguous, general-knowledge and sensitive questions are asked by `exec`. This gives 361 rows in standard mode
and 375 in grounded mode (the 14 general-knowledge and sensitive rows run only grounded).

Each row's expectation is derived from the evidence documents and the layout, never typed:

| Expectation | When | A pass means |
| --- | --- | --- |
| `answer` | The role reads every evidence document | The gold answer is given (139 such rows per mode) |
| `partial` | It reads some of them | Only what it can read is stated, and the rest is said to be missing |
| `abstain` | It reads none, or the fact is absent | No wrong fact is asserted; declining passes |
| `follow_gold` | Near miss or false premise for a role that reads the evidence | It does what the gold answer describes (name the right period, correct the premise) |

Documents a role may not read are its forbidden documents for that row.

### 4.4 Comparability

A result names its corpus fingerprint, question-set version (frozen 2026-10-06), judge and API build. Changing any
of them is a new baseline, never a comparison.

## 5. The RAG flow being measured

| Step | What it does |
| --- | --- |
| A1 Extract text | docling-serve, Tika as fallback |
| A2 Read scanned pages | OCR, only for scanned PDF pages and images |
| A3 Cut into chunks | At most 768 tokens, along headings and tables |
| A4 Create vectors | Embedding model |
| A5 Index | OpenSearch |
| B1 Check limits | Spending and size limits |
| B2 Guardrail | Guardrail model blocks disallowed topics |
| B3 Rewrite question | Chat model writes a semantic and a keyword query; source and time inference run beside it |
| B4 Readable scope | The Sources the user may read |
| B5 Hybrid search | BM25 and vector search in one hybrid query per rewritten query, the queries merged by RRF, to depth 50 |
| B6 Choose passages | The model picks up to 10 sections of the 50 |
| B7 Add nearby text | Two adjacent chunks on each side of a chosen section |
| B8 Write answer | Chat model, with the grounded prompt in grounded mode |
| B9 Check citations | Every `[n]` must name a source of the message |
| B10 Send answer | Redis Streams to the browser over SSE |

Stage 1 measures A1 to A5, stage 2 measures B5 alone (no rewrite), stage 3 measures B3 to B7 together, stage 4 the
reply.

## 6. How a run is made

1. **Sign in** each role once (`login`); the offline tokens are kept on the server. Concurrent renewals are serialised
   by a file lock.
2. **Check the corpus** (`check`): all 61 documents uploaded and searchable.
3. **Build the config** (`build_config.py --mode standard|grounded|retrieval`): one promptfoo test per row, with the
   question, role, gold answer, gold documents, quotes, forbidden and tempting documents, and the assertions.
4. **Run** `promptfoo eval` inside the container, 2 at a time, no cache.
5. **Ask again** the rows that ended in an error (`--filter-errors-only`), and merge them.
6. **Re-grade** without new turns when only the checks change: stored replies are replayed (`export_replay.py`, replay
   provider) and graded again; the stage metrics of the 2026-10-08 replies were measured this way.
7. **Export** each eval (`promptfoo export eval`), reduce it to compact rows (`export_rows.py`) and keep them in
   `results/`, with the run's date, API build and eval ids in `results/runs.json`.
8. **Summarise** with `summary.py`: rates, intervals, paired differences and strata.
9. **Record** the results in `docs/tests/chat.md` and the reference workbook (`report/build_workbook.py`).

## 7. Metrics

A row is one question asked by one role. Rates are the mean of a row's score over the rows a metric applies to.
Deterministic metrics (no model involved) are computed in `assertions.py`; judged metrics use a judge model with the
rubric quoted in `build_config.py`.

### 7.1 Stage 1: extraction

**Extraction coverage.** The share of a question's evidence quotes found in the indexed text of their document.
Found means at least 90% of the quote's character 6-grams occur in the text, after keeping only letters and digits
(extraction splits words and figures: "M inh", "46.621.079. 163") and counting each fragment of the quote (joined by
" | " or on its own line, as table cells are) separately.

Example: a question has two quotes; the first is in the index, the second is a table row whose figures OCR misread in
three places out of forty 6-grams (92.5% found, so still found). Coverage is 2/2 = 100%. Had the row been missing,
coverage would be 1/2 = 50%.

### 7.2 Stage 2: retrieval

One search per question as `exec`, depth 50, without the rewrite, over the 97 questions `exec` must answer or follow.
The gold documents are the question's evidence documents.

| Metric | Definition | Example: gold {A, B}, A at rank 3, B at rank 14 |
| --- | --- | --- |
| Recall@10 | Gold documents in the top 10, divided by gold documents | 1/2 = 0.50 |
| Recall@50 | The same at depth 50 | 2/2 = 1.00 |
| nDCG@10 | Sum of 1/log2(rank+1) over gold in the top 10, divided by the best possible sum for that many gold documents | (1/log2 4) / (1 + 1/log2 3) = 0.5 / 1.63 = 0.31 |
| MRR@10 | 1 / rank of the first gold document in the top 10, else 0 | 1/3 = 0.33 |
| Passage recall@10 | Evidence quotes found (as in 7.1) in the sections returned with the top 10 documents | if A's returned section holds its quote and B's quote is not in the top 10: 1/2 = 0.50 |
| Tempting first | The question's tempting document (another period or company) ranks above every gold one | counted over the 25 questions that have one |

Recall says whether the evidence is found at all; nDCG and MRR say how high it is ranked; passage recall says whether
the right part of the document comes back, which is what the model can read.

### 7.3 Stage 3: what the model read

From the documents of the sections given to the model during the turn (`readDocuments`), over rows where the role may
read at least one gold document (215 rows per mode):

| Metric | Definition | Example: gold {A, B}, the model read {A, C, D} |
| --- | --- | --- |
| Context recall | Gold documents read / gold documents the role may read | 1/2 = 0.50 |
| Context precision | Gold documents read / documents read | 1/3 = 0.33 |

Only evidence documents count as relevant, so a correct second source lowers precision: it is a lower bound.

### 7.4 Stage 4: the reply

| Metric | Applies to | How | Pass means |
| --- | --- | --- | --- |
| Leakage | Every row | Deterministic | No forbidden document was read or cited |
| Tempting | Every row | Deterministic | The tempting document was not cited |
| Citation | Grounded rows | Deterministic | Every inline `[n]` names one of the message's sources |
| Correctness | `answer` rows | promptfoo `factuality` against the gold answer | The reply agrees with the gold answer; extra true facts are allowed |
| Completeness | `answer` rows | `llm-rubric` | Every point the question asks for is answered with the gold value; details the question did not ask for are not required |
| Faithfulness | `answer` rows | `llm-rubric` over the reply and the cited text | Every fact, figure, name and date in the reply is in the cited text, or follows from it by a correct calculation |
| Behaviour | `partial`, `abstain`, `follow_gold` rows | `llm-rubric` per expectation | The reply does what the expectation in 4.3 says |
| Rows passing every check | Every row | All checks of the row pass | The headline rate |

Example: the question asks for Q1/2026 net profit. A reply that gives the Q1/2025 figure fails correctness and, if it
cites the Q1/2025 statement (the tempting document), fails tempting too. A reply that gives the right figure and adds
the CEO's name passes correctness; if the name is not in any cited passage, it fails faithfulness.

**Judges.** The reference judge `cx/gpt-6-luna` is also the chat model, so `cx/gpt-6-sol` grades correctness and
completeness of the same replies. Agreement is the share of rows both judges grade the same; Cohen's kappa is that
agreement corrected for chance (1 is perfect, 0 is chance; above 0.8 is usually read as strong). The completeness
rubric was calibrated by hand on 20 replies before use (section 10).

### 7.5 Operation

| Metric | Definition |
| --- | --- |
| Turn time p50 / p95 | From sending the question to the end of the reply; nearest-rank percentiles |
| Tokens per turn | Input and output tokens recorded on the reply message |
| Cost per turn | Recorded usage at list price; `cx/` models actually run on a subscription |

## 8. Uncertainty

Every rate carries a 95% bootstrap interval: draw the questions with replacement 10,000 times (seed 230), recompute the
rate each time, and take the 2.5th and 97.5th percentiles. Questions are resampled, not rows, because one question is
asked by several roles and its rows are not independent.

A difference between two runs is paired: the same questions are drawn for both runs in each resample, and the interval
of the difference is reported. **An interval that includes zero is reported as no measured difference.** Example:
hybrid minus keyword nDCG@10 is +7.0 points [1.9, 12.1]: the whole interval is above zero, so hybrid ranks better.
Hybrid minus vector is +4.1 [−0.5, 8.9]: it includes zero, so it is not shown to be better.

Re-grading the same stored replies moves a single check by up to 5 rows, so a difference of that size between two runs
is judge noise. Results are also shown by stratum: text layer of the gold documents, category and split.

## 9. Step ablation

To measure one step, a run is made with that step removed and compared, row by row, with the released pipeline (V0) on
the same questions.

1. **Probe image.** One class of the released API is changed so that an environment variable
   (`MEMORYOS_SEARCH_ABLATION`) removes one step (`keyword` or `vector` keeps one side of the hybrid query; `rewrite`
   skips the query rewrite; `neighbours` skips the adjacent chunks). Everything else (filters, access rechecks, depth)
   is unchanged. The class is compiled against the released image's libraries and placed on top of that image. The
   probe is never merged ([ADR 0002](../../docs/decisions/0002-no-speculative-operational-surfaces.md)).
2. **Swap.** Staging deployment is paused, the API container is recreated on the probe image, a smoke turn is checked,
   and the run is made. Afterwards the released API is restored and deployment re-enabled.
3. **Compare.** Rows passing and each metric, paired with V0. A variant that is worse, or that saves nothing, keeps
   the step.
4. **Repeat** when a difference is near the noise: V1 (no rewrite) was run twice.

Variants run on 2026-10-31: V1 no rewrite (twice), V6 no adjacent chunks; single-side retrieval for keyword and vector.
Earlier variants of 2026-10-06 (P8a) are in the increment plan.

## 10. Calibration and checks of the checks

- **Completeness rubric.** The first rubric ("every point of the gold answer") failed 13 of 20 hand-read replies that
  answered fully, because gold answers also name the article or resolution number. The rubric was rewritten to "every
  point the question asks for"; it then agreed with the hand reading on all 20, for both judges.
- **Partial and abstain rubrics.** A role that may not read the evidence can still read the same fact elsewhere;
  judging it on "declined" failed 9 of 30 audited correct and leak-free replies, so it is judged on not asserting
  anything wrong, and leakage still catches a forbidden read.
- **Quote matching.** Word matching failed on split words and figures; character 6-grams on compacted text, per
  fragment and per line, were adopted after checking matches by hand.
- **Metric code** is unit-tested against hand-computed values (`tests/test_stage_metrics.py`): ranking metrics,
  quote matching on split figures and table cells, bootstrap and percentile, kappa.

## 11. Limitations

- **One corpus, one language, one company's disclosures.** Results describe these 61 documents and 120 questions, not
  every customer's data.
- **Judged metrics depend on the judge.** Scores are compared only under one judge; a second judge is reported beside
  the first.
- **The chat model is also the reference judge.** The second judge agrees on 95.7 to 98.6% of rows, which gives no sign
  of self-preference, but does not rule it out.
- **Recall@50 says nothing here:** with 61 documents a depth of 50 holds nearly the whole corpus.
- **Context precision is a lower bound** (section 7.3).
- **Source and time inference are not measured:** every benchmark upload has one date and one source type.
- **Retrieval is measured as `exec` without the rewrite**, so it measures the index, not what Chat searches for.
- **Staging is shared:** other staging traffic can affect turn time.

## 12. Where the evidence is

| Item | Location |
| --- | --- |
| Corpus, layout, questions, review sample | `corpus/` |
| Authoring brief and scripts | `authoring/` |
| Providers, assertions, config builder, summary | `promptfoo/` |
| Per-row results and run list | `results/`, `results/runs.json` |
| Reference workbook | `report/build_workbook.py` builds it from `results/` and `corpus/` |
| Reported figures | [docs/tests/chat.md](../../docs/tests/chat.md#rag-benchmark-baseline-mem-230--2026-10-08) |

## 13. Why these metrics and methods

Every choice below names what it is for, the alternatives considered and why they were not taken. The reference
points are the public benchmarks and tools the field uses: BEIR (a standard collection of retrieval benchmarks,
scored by nDCG@10 and recall), MS MARCO (Microsoft's passage-ranking benchmark, scored by MRR@10), RAGAS (an
open-source RAG evaluation library: context recall, context precision, faithfulness), TREC RAG (the RAG track of the
US NIST evaluation campaign: completeness and citation support), and Onyx, the open-source enterprise search product
MemoryOS is compared with (recall@k, MRR and paired 95% intervals in its retrieval benchmarks; correctness,
completeness and document recall in EnterpriseRAG-Bench).

### 13.1 Metrics

| Choice | Why | Alternatives not taken |
| --- | --- | --- |
| Score every stage separately (extraction, retrieval, context, answer) | A wrong answer alone does not say what to fix. If the quote is not in the index, the fix is extraction; if it is indexed but not ranked, search; if ranked but not read, the passage choice; if read but answered wrongly, the prompt or model | Scoring only the final answer, as the first baselines did: about a third of rows failed with no sign of where |
| Extraction coverage | The only way to see OCR and parsing loss before search hides or reveals it; it needs no model | Comparing the whole extracted text with the original by edit distance: needs a perfect reference text of 1,750 pages, which does not exist for scans |
| Recall@10 | The model is given about ten sections, so a document outside the top 10 is effectively lost. BEIR and Onyx report it | Recall@1, @3, @5 (Onyx also reports them): too strict when a question has two gold documents. Recall@50 is reported but says nothing on a 61-document corpus |
| nDCG@10 | Rewards ranking gold documents higher and handles questions with several gold documents; BEIR's main metric | Mean average precision: similar, less common in RAG reports and harder to explain |
| MRR@10 | How far down the first useful result is; the metric of MS MARCO and Onyx | nDCG alone: MRR is easier to read ("the first gold document is, on average, near rank 2") |
| Passage recall@10 | A document can be found while the wrong part of it is returned, and the model reads sections, not documents | Matching chunk ids: they change on every re-index, quotes do not |
| Tempting first | The weakest category is answering from another period or company (near miss 23 to 30%); this shows whether search or the model causes it | |
| Context recall and precision | Whether the model actually had the evidence, as in RAGAS, computed by code from the documents it was given | promptfoo's and RAGAS's judge-graded versions: a model would guess what the code knows exactly |
| Correctness by `factuality` | Replies are worded differently from the gold (units, number formats, order), so string matching fails; promptfoo's built-in check classifies agreement and contradiction | Exact match or word F1 (SQuAD style): fails "0,35 tỷ đồng" against "350.000.000 đồng" |
| Completeness | A correct but partial answer passes correctness; TREC RAG and Onyx score completeness separately | Folding it into correctness: hides a reply that gives one of two asked figures |
| Faithfulness | A right answer can still state facts no cited passage supports, which a reader cannot check; RAGAS and TREC RAG score it | |
| Leakage, tempting and citation by code | These are yes-or-no facts the system records (documents read and cited, the `[n]` that exist); a judge could only add error | A judge: slower, costs money and can be wrong |
| Behaviour rubrics for decline, partial and follow-gold rows | The right reply is a behaviour (decline, say what is missing, correct the premise), not a fact to compare | promptfoo's `is-refusal`: detects refusal wording but cannot judge a partial answer |
| Turn time p50 and p95, tokens, cost | A step that improves quality but doubles time or cost may not be worth it; p95 shows the slow turns users notice | The mean alone: distorted by a few very slow turns |
| No answer relevancy (RAGAS) | Scored by embedding similarity and unstable; behaviour already checks that the reply answers the question | |

### 13.2 LLM as a judge

Code cannot decide whether two differently worded answers agree, or whether a reply declined properly, and grading
every reply of every run by hand (736 replies per baseline) is not possible. A language model grades these, under
these controls:

| Control | What it prevents |
| --- | --- |
| A written rubric per check, quoted in `build_config.py`, given with the gold answer | The judge's own taste deciding what counts as correct |
| Calibration on hand-read replies before use (section 10) | A rubric stricter or looser than a human reader |
| A second judge model (`cx/gpt-6-sol`) on the same replies, with agreement and Cohen's kappa | One model's bias, including favouring its own replies (the chat model is also Luna) |
| Deterministic checks wherever code can decide | Judge error on yes-or-no facts |
| Re-grading the same replies to measure judge noise (up to 5 rows) | Reading judge noise as a system change |
| One judge per comparison | Changing the judge moved correctness from 0.667 to 0.805 on the same answers (MEM-141), so scores under different judges are never compared |

The judges are OpenAI models and the questions were written by Claude, so no model grades questions it wrote.

### 13.3 Methods

| Choice | Why | Alternatives not taken |
| --- | --- | --- |
| A public corpus of a real listed company | Real documents have the scans, tables, conflicts and near-duplicates of company data; public ones can be shared and downloaded again by anyone | Customer documents: cannot be published. Synthetic documents: too clean |
| Noise from a second company | Real search must not answer from a similar company's documents | No noise: every question is easy to route |
| Questions written from the original files, by a model that never sees MemoryOS | Writing from MemoryOS's own chunks only asks what MemoryOS already extracted well | Questions written from the system's index (the MEM-141 set) |
| Fixed quotas and a seeded page sample | Stops the writer picking easy, headline facts | Free choice of questions |
| Blind validation by two models | Removes ambiguous questions and those whose quote does not support the gold | The author's own check |
| Roles and derived expectations | Access control is a requirement; deriving expectations from the layout keeps them right when Groups change | Asking every question as an administrator, which never tests access |
| promptfoo as the runner | Open source and self-hosted (no data leaves the server), built-in judge checks, providers and assertions in Python, stored results and a viewer for every row | A hand-written runner (MEM-141: hard to maintain, no viewer); RAGAS alone (no access roles, no provider for a streaming chat); hosted evaluation services (data leaves the server) |
| Bootstrap intervals over questions | 97 to 215 rows per metric is a small sample; an interval shows how far a score could move with other similar questions. Resampling questions, not rows, respects that one question has several role rows | No interval (a 2-point change would look real); a normal-approximation interval (wrong near 0% and 100%) |
| Paired comparison | The same questions in both runs remove the difference between questions, so a smaller real difference becomes visible | Comparing two separate intervals: overlapping intervals can hide a real paired difference |
| Ablation on a probe image | Shows what one step contributes with everything else unchanged | A switch in the product: a temporary runtime mode, which ADR 0002 forbids |
| Two runs of a variant near the noise | A 2-point difference can be judge noise; repeating it shows whether it keeps its direction | One run |
| A dev/test split | Decisions are read on questions not used for tuning, so the system is not fitted to the benchmark | Using all questions for both |
