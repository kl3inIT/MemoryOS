# MemoryOS minutes benchmark

Measures the meeting minutes MemoryOS writes: how many of the decisions and actions a careful note-taker would
record they find, how much of what they write is right, and whether each action's owner is the person who will do
it. It runs on promptfoo on staging beside the [RAG benchmark](../rag-benchmark/README.md), whose login and API
client it uses. Design: [MEM-304](../../docs/increments/active/mem-304-minutes-benchmark/design.md); baseline:
[docs/tests/meeting.md](../../docs/tests/meeting.md#minutes-benchmark-baseline-mem-304-2026-11-02).

Transcripts, the answer key and results are private meeting content: they stay in
`/apps/memoryos/promptfoo/minutes/` on the staging server (`/workspace/minutes` in the container) and never in git.

| Path | Holds |
| --- | --- |
| `copy.py` | copies the source meetings into the *Minutes Benchmark* account, once each (staging host) |
| `export_lines.py` | the copies' numbered transcripts, for drafting and reviewing the key |
| `KEY.md` | what the answer key holds and how an owner is decided |
| `promptfoo/minutes_provider.py` | has MemoryOS write a copy's minutes again and returns them |
| `promptfoo/assertions.py` | lines written items up with the key (one judge call) and counts every metric |
| `promptfoo/build_config.py`, `summary.py`, `regrade.py` | the run config, its summary, and scoring stored minutes again |

## Run

```bash
docker exec -w /workspace/minutes/promptfoo -e MEMORYOS_BENCH_TOOLS=/workspace/bench \
  -e MEMORYOS_BENCH_HOME=/home/promptfoo/.promptfoo/memoryos memoryos-promptfoo sh -c '
  python3 build_config.py --copies /workspace/minutes/copies.json --keys /workspace/minutes/key \
    --label "<what changed>" --out /tmp/minutes.json &&
  OPENAI_API_KEY=$(cat /run/secrets/promptfoo_judge_api_key) \
    promptfoo eval -c /tmp/minutes.json --repeat 3 -j 2 --no-cache --no-progress-bar --no-table'
promptfoo export eval <eval id> -o /home/promptfoo/.promptfoo/<run>.json       # in the container
python3 summary.py /home/promptfoo/.promptfoo/<run>.json                       # in the container
```

A change is measured after it is deployed to staging. The minutes are written with the Tenant's `MEETING_MINUTES`
model, so a model comparison sets that model for the run and says so with the result. Repeats of one meeting wait
for each other (a file lock per meeting), and two runs at a time keep the writer and the judge, which share the
9Router accounts, within their limits. A change to the scoring alone is measured on stored minutes with
`regrade.py`, without new writings.

The corpus fingerprint is in `copies.json` and the key's in `KEYS`; results on another fingerprint are not
comparable. Meetings 07 and 12 are left out as the same meetings as 06 and the first half of 13.

## Tests

```bash
uv run --no-project --with pytest pytest tests
```
