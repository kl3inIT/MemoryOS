# MemoryOS RAG benchmark

Measures what a retrieval or Chat change did: whether the reply answers correctly from the documents it cites,
declines when the corpus cannot answer, keeps to the right period and company, and never reaches a document the
asking role may not read. It runs on [promptfoo](https://www.promptfoo.dev/) on staging over a corpus of its own.

Design and delivery: [MEM-230](../../docs/increments/active/mem-230-rag-bench-promptfoo/design.md). The current
baseline is in [docs/tests/chat.md](../../docs/tests/chat.md#rag-benchmark-baseline-mem-230--2026-10-06).

## Layout

| Path | Holds |
| --- | --- |
| `corpus/manifest.json` | the 61 public disclosures (49 Savico, 12 Haxaco as near-miss noise): stable id, department, period, sha256, URL |
| `corpus/layout.json` | the `Benchmark - …` Sources and Groups, the roles (`exec`, `finance`, `legal`, `governance`, `ir`, `hr`, `outsider`) and the grounded agent |
| `corpus/questions.jsonl` | the 120 frozen questions, each with evidence passages and an expectation per role |
| `memoryos_bench/` | login, provisioning, fingerprint and the Chat client; Python standard library only |
| `authoring/` | how the questions were drawn, validated and frozen ([brief](authoring/BRIEF.md), [review](corpus/review.md)) |
| `promptfoo/` | the provider, the custom assertions and the config builder |

## Corpus

```bash
cd tools/rag-benchmark
python -m memoryos_bench login --role owner exec finance legal governance ir hr outsider
python -m memoryos_bench provision      # idempotent; prints the corpus fingerprint
python -m memoryos_bench fingerprint
```

`login` keeps offline tokens of the public `memoryos-integration` client (PKCE) in `$MEMORYOS_BENCH_HOME`
(default `~/.memoryos-bench`), with the download cache. A baseline records the fingerprint it ran on; results on
another fingerprint are not comparable.

## Run

`build_config.py` writes one promptfoo config per mode: `standard` asks the built-in assistant, `grounded` the
`Benchmark - grounded` agent. Each question is asked by the roles its department and category name, and each reply is
scored by the role's expectation:

| Check | Metric | How |
| --- | --- | --- |
| A document the role may not read was read or cited | `leakage` | `assertions.py` |
| The question's tempting document (another period or company) was cited | `tempting` | `assertions.py` |
| Every inline `[n]` names one of the message's sources (grounded) | `citation` | `assertions.py` |
| An answerable row matches the gold answer | `correctness` | `factuality` |
| Every fact in the answer is in the cited text | `faithfulness` | `llm-rubric` |
| A decline, partial or behaviour row does what its rubric states | `behaviour` | `llm-rubric` |

```bash
python promptfoo/build_config.py --mode grounded --out promptfoo/grounded.yaml
python promptfoo/build_config.py --mode grounded --only cross-039 --out promptfoo/smoke.yaml
```

On staging the workspace is `/apps/memoryos/promptfoo/bench`, mounted in the `memoryos-promptfoo` container at
`/workspace/bench`. The judge is `cx/gpt-6-luna` through 9Router, its key read from the viewer's secret:

```bash
docker exec -w /workspace/bench/promptfoo -e MEMORYOS_BENCH_HOME=/home/promptfoo/.promptfoo/memoryos \
  memoryos-promptfoo sh -c 'OPENAI_API_KEY=$(cat /run/secrets/promptfoo_judge_api_key) \
  promptfoo eval -c grounded.yaml -j 2 --no-cache --no-progress-bar --no-table'
```

Keep 2 concurrent turns: Chat and the judge share the two Codex accounts behind 9Router, and 3 or more lock both
(MEM-231). Results open in the viewer at `MEMORYOS_PROMPTFOO_PUBLIC_URL`, behind OAuth2 Proxy and the
`memoryos-inspector` role.

## Re-grading without new turns

A change to the checks alone is measured on stored replies:

```bash
python promptfoo/export_replay.py <eval id> /home/promptfoo/.promptfoo/replay.json   # inside the container
python promptfoo/build_config.py --mode grounded --replay --out promptfoo/replay-grounded.yaml
MEMORYOS_REPLAY=/home/promptfoo/.promptfoo/replay.json promptfoo eval -c replay-grounded.yaml -j 2 --no-cache
```

Re-grading unchanged replies moves a single check by up to 5 rows; read smaller differences between runs as judge
noise.

## Tests

```bash
uv run --with pytest pytest
uv run --with ruff ruff check .
uv run --with ruff ruff format --check .
```
