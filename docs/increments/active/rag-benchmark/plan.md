# Plan

Runs from a developer machine against staging through its public API. Private data stays under
`.tmp/rag-benchmark/` (ignored). Secrets come from Infisical `staging`: `RAG_BENCHMARK_OPENROUTER_API_KEY` (generator
and judge, `qwen/qwen3.8-27b`), `SPRING_AI_OPENAI_API_KEY` (`text-embedding-3-large`), and the benchmark password.

## Phases

| # | Step | Who | Output | Gate |
|---|---|---|---|---|
| 0 | Invite `rag-benchmark@memoryos.test` on staging; add it to the Group that reads the benchmark Sources | Owner | Invitation | Invitation sent |
| 1 | Activate from staging Mailpit; store `RAG_BENCHMARK_USERNAME`/`RAG_BENCHMARK_PASSWORD` in Infisical; `rag-bench auth` signs in through `memoryos-integration` (Authorization Code + PKCE) and calls `/api/identity/me` | Claude | Token, member check | `me` returns the benchmark actor |
| 2 | `tools/rag-benchmark/` (uv): config, dataset schema, run manifest, `auth`, `export` | Claude | `corpus/*.jsonl`: each current document's text rebuilt from its chunks in ordinal order, with chunk boundaries and provenance | 58 documents, token totals match staging |
| 3 | `generate`: sample excerpts per document and category quota; Qwen writes a question and copies the exact excerpt; filters: excerpt found verbatim, not too short, embedding-deduplicated; plus unanswerable cases | Claude | `cases.review.csv` (~120 rows) | — |
| 4 | Review: keep, fix or drop each row | Owner | `cases.jsonl` (~80–100 reviewed) | Owner approves the set |
| 5 | `chunking`: current chunks; packed 256-token floor with 512/768/1,024 ceilings; fixed 256/512/1,024 baselines; embed, retrieve top results in memory | Claude | Token recall, precision, IoU by chunker and file type | — |
| 6 | `search`: `/api/search` per case | Claude | hit@1/3/5/10, MRR, excerpt token recall/precision, p50/p95 latency | — |
| 7 | `chat`: one session per case with a chosen model other than the judge; wait for the outcome; read answer, sources, usage | Claude | Deterministic: citation precision, abstention, tokens, cost, latency. Judged: FactualCorrectness, Faithfulness | — |
| 8 | `report`: summary by layer and category, per-case table, comparison with a baseline run | Claude | `runs/<timestamp>/report.html` + summary numbers without content in `docs/tests/` | Owner reads the baseline |
| 9 | Deep research on multi-part cases (`chat --research`) | Claude | Same answer metrics | Optional |

## Commands

```sh
cd tools/rag-benchmark
uv run rag-bench auth                      # sign in, check membership
uv run rag-bench export                    # corpus from the benchmark member's current documents
uv run rag-bench generate --cases 120      # cases.review.csv
uv run rag-bench approve cases.review.csv  # reviewed rows -> cases.jsonl
uv run rag-bench chunking                  # offline chunking layer
uv run rag-bench search                    # live search layer
uv run rag-bench chat --model <model-configuration-id>
uv run rag-bench report [--baseline runs/<id>]
```

Each command writes under `.tmp/rag-benchmark/`; a run directory records the dataset hash, git SHA, settings,
models and judge.

## Estimated cost per full run

Embeddings for six chunkers (about 4.3M tokens) about US$0.6; generation about US$0.3; chat on 100 cases about
US$1–3 depending on the model; judging about US$0.5–1. Under US$5.

## Checklist

- [x] Design and accepted decisions.
- [x] 0–1 Benchmark member bound directly in the staging DB at the owner's request; bearer sign-in verified (`/api/identity/me` 200).
- [ ] 2 Project, `auth`, `export`.
- [ ] 3 Case generation.
- [ ] 4 Review (owner).
- [ ] 5 Chunking layer.
- [ ] 6 Search layer.
- [ ] 7 Chat layer.
- [ ] 8 Report and first baseline.
- [ ] 9 Deep research layer.
