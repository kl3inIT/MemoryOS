# run_python evaluation

Measures what a change to the `run_python` guidance (`RUN_PYTHON_GUIDANCE` in
[`ChatPrompts.java`](../../memoryos-core/src/main/java/io/memoryos/chat/prompts/ChatPrompts.java)) or to the sandbox
computer's [`sitecustomize.py`](../../memoryos-sandbox/computer/sitecustomize.py) does to a real model's data analysis.
Run it when either changes, and when the computer's pandas, numpy or Python move.

Ten tasks, phrased as a Vietnamese user asks in Chat, each print one JSON answer that is scored against a reference
computed on the same packages: filling blanks in place, monthly, quarterly and hourly totals, a conditional update,
normalizing text columns, forward fill, the trapezoid rule, positional access and numpy scalars. They lean on what
changed under the model (pandas copy-on-write and frequency aliases, numpy 2 removals) and on `dayfirst=True` with ISO
dates, which caused every wrong answer in the first run.

Each arm is a git ref; its guidance and `sitecustomize.py` are read from the repository at that ref. For every task
and repeat the model writes one script, the script runs, and a failing run gets one fix attempt with its traceback, as
in Chat. Both arms run on the same packages: the computer's locked environment, or its image. An arm's runtime is
`sandbox` by default; `interpreter`, removed by MEM-271, reads its `sitecustomize.py` from `interpreter/` and so needs a ref that has it
(`d513a1c9` is the last on main); it runs only in an executor image.

## Setup

```bash
uv sync --directory memoryos-sandbox/computer --frozen --no-dev   # the computer's exact packages, for references and --sandbox local
cp tools/interpreter-eval/.env.example tools/interpreter-eval/.env   # fill it; never commit values
```

The model is any OpenAI-compatible chat endpoint. The default model, `cx/gpt-6-luna`, takes only its default
temperature, so none is sent, and replies are not streamed.

## Run

From `tools/interpreter-eval`, with the variables of `.env` exported:

```bash
# Recommended: model-written code runs in the computer image, with no network.
../../memoryos-sandbox/computer/.venv/bin/python -m interpreter_eval \
  --baseline origin/main --candidate WORKTREE --sandbox docker \
  --image ghcr.io/kl3init/memoryos-sandbox-computer:<tag>

# The retired interpreter against the sandbox, as the MEM-271 gate ran: each arm in its own image.
../../memoryos-sandbox/computer/.venv/bin/python -m interpreter_eval \
  --baseline d513a1c9 --candidate WORKTREE --sandbox docker \
  --baseline-runtime interpreter --baseline-image ghcr.io/kl3init/memoryos-interpreter-executor:<tag> \
  --candidate-image ghcr.io/kl3init/memoryos-sandbox-computer:<tag>

# Without Docker: the computer's virtual environment, in a temporary directory, with no credentials in its
# environment. The code still runs on this machine.
../../memoryos-sandbox/computer/.venv/bin/python -m interpreter_eval --baseline origin/main --candidate WORKTREE --sandbox local
```

On Windows the Python is `..\..\memoryos-sandbox\computer\.venv\Scripts\python.exe`. `--repeats` (default 5),
`--workers` (default 6) and `--task` (repeatable) narrow a run; `--out` names the results file. The summary prints a
table of correct first runs / correct after one fix per task and arm, and the count of answers that were wrong without
an error. Compare runs only under the same model: a different model moves the scores more than most prompt changes.

## Tests

```bash
python -m unittest discover -s tools/interpreter-eval/tests -t tools/interpreter-eval
```

They cover scoring and reading the arms, and need only the standard library. CI runs them in the `infrastructure` job.

## Results

| Date | Baseline → candidate | Model | Correct after one fix | Wrong with no error |
| --- | --- | --- | --- | --- |
| 2026-10-03 | main → [#500](https://github.com/kl3inIT/MemoryOS/pull/500) (Python 3.14, pandas 3, numpy 2.5, guards) | `cx/gpt-6-luna` | 35/50 → 50/50 | 15 → 0 |
| 2026-10-18 | interpreter on main → sandbox on [#751](https://github.com/kl3inIT/MemoryOS/pull/751) (MEM-271: computer image, `attachments/`, files that stay) | `cx/gpt-6-luna` | 48/50 → 49/50 (first run 35 → 38) | 2 → 0 |
