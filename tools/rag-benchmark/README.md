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

The realm enables no password grant, so each actor authenticates through its own service-account client, or through a
bearer token exported for a single run. Group questions need two actors: one in the Groups that hold the granted
Sources and one without them.

## Use

```bash
rag-benchmark freeze                 # write datasets/corpus.json, the corpus every run compares against
rag-benchmark check                  # validate questions and report corpus drift
rag-benchmark run --retrieval-only   # search page only: recall@k and nDCG@5
rag-benchmark run --label baseline --save-baseline
rag-benchmark run --label chunk-512  # compared against the baseline; exits non-zero on a regression or a leak
```

Each run writes `runs/<label>.json` (every question's retrieval, citations, answer and verdict) and
`runs/<label>.md` (the table a person reads). A leak fails the run on its own, whatever the other metrics say.

`/api/search` measures retrieval alone; a Chat run adds query rewriting, filters and section selection. Read a
chunking or index change from the retrieval metrics and a prompt or tool change from the Chat metrics.

## Questions

`datasets/questions.jsonl`, one JSON object per line, categories `lookup`, `multi_hop`, `aggregate`, `temporal`,
`abstain`, `ambiguous` and `group`. Every gold document id comes from the frozen corpus; `check` names any that does
not. Write each question from a passage that was read, not from memory.

## Tests

```bash
uv run pytest        # metrics, question validation and the regression gate
uv run ruff check .
uv run ruff format --check .
```
