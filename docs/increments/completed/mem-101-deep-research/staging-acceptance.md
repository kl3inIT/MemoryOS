# MEM-101 staging acceptance script

Deep research reaches staging only through `main`: `deploy-staging.yml` runs on a successful `main` CI run, and its manual dispatch also requires `refs/heads/main` plus a main CI run id. So this script runs after PR #202 merges and the staging deployment reports healthy, not before.

## Prerequisites

| Item | Why | Owner |
| --- | --- | --- |
| PR #202 merged and staging deployed from that `main` CI run | Staging serves `main` only | Repository owner |
| A staging tenant with the real corpus indexed and readable by the acceptance actor | Research cites authorized documents only | Owner |
| A chat model with tool calling and a context window of at least 50,000 tokens, selected for the session | `CHAT_RESEARCH_UNAVAILABLE` otherwise | Owner |
| An external Web search connection, or a persona with internal Search | Otherwise the composer button stays hidden | Owner |
| Browser sign-in for the acceptance actor, plus a second actor without `MODELS_MANAGE` | The setting checks need both authorities | Owner |

## Checks

Record the result, the assistant message id and the observed evidence for each row.

| # | Check | Expected |
| --- | --- | --- |
| 1 | Composer shows the Deep research button outside Projects; open a Project chat | Button present in a normal chat, absent inside a Project |
| 2 | Send a vague question ("báo cáo tài chính thế nào") with Deep research on | One clarification question block, no plan, no agents; history shows `research.clarification` true |
| 3 | Answer the clarification | No second clarification; the plan streams, then agents appear |
| 4 | A question that needs several directions | Two or three agent tabs in one cycle, each with its task, steps and an intermediate report |
| 5 | Open the intermediate report of an agent | Markdown renders; citation numbers match the answer's sources |
| 6 | Click a citation in the final report | Opens the document the agent actually read; a document the actor cannot read never appears |
| 7 | Reload the page mid-run | Progress continues from the stream or history; no duplicate plan or agent tabs |
| 8 | Stop during an agent's search | Turn ends CANCELED, partial output kept, running agents saved as failed, no final report |
| 9 | Ask again with Deep research off | Ordinary answer, no research block |
| 10 | Turn the tenant setting off as a manager, then send a research command | Button hidden for members; the API rejects with `CHAT_RESEARCH_UNAVAILABLE` |
| 11 | A member without `MODELS_MANAGE` opens Chat settings | Reads the setting, cannot change it |
| 12 | The observability backend after the run (metrics leave through the OTLP registry, so names keep their dots and the collector maps them downstream) | `memoryos.chat.research.phase.duration` by `phase` and `outcome`, `memoryos.chat.research.agents` by `outcome`, `memoryos.chat.research.cycles`, `memoryos.chat.research.forced.reports`, and the `memoryos.chat.research.phase` spans; no question, task or document content in any label or span attribute |
| 13 | Cost and tokens on the persisted message | `input_tokens`, `output_tokens` and `cost_usd` are not null for a completed research turn |

## Recording

Add the result table, run identifiers and screenshots to this file, then reconcile the plan item and the [Chat verification matrix](../../../tests/chat.md#deep-research-mem-101).
