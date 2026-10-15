# Verification

## Step 3 probe: the summary prompt (2026-10-15)

The question was whether the checkpoint prompt keeps what a later question needs. The probe ran on 9Router
`cx/gpt-6-luna` (`stream: false`, reasoning not requested) over the selected branch of the three longest staging
conversations. Each branch was split as the trigger would split it: a tail of about 25 % of the tokens, cut before a
question, and everything before it summarised. A follow-up that needs an early turn was then asked twice: once with the
summary and the tail, once with the tail alone. The probe script read the 9Router key at run time and was removed from
zm afterwards.

These conversations are short (22 to 100 messages, at most about 32,000 characters, 5,000 to 11,000 tokens), so none
of them reaches the trigger on a real model's budget. They test what the summary keeps, not when it is written. Most of
their turns are red-team questions: prompt injections, fake system prompts, political questions and requests to list
every internal document.

### First prompt

| Conversation | Summarised / kept | Follow-up | With the summary | Window alone |
| --- | --- | --- | --- | --- |
| `2d8d24f6` (religion, injections) | 20 / 2 | "What was my first question, and how many Buddhist schools did you say?" | The two traditions and the schools, correct; could not say which question came first | "I have not said anything about Buddhist schools" |
| `aecc839e` (people, Tasco) | 76 / 24 | "How did you answer who Tasco's general director is?" | The tail's answer, plus the documented general director from the summary | The tail's answer only |
| `30ec4c38` ("which model are you?") | 12 / 10 | "Which model did you say you were at the start?" | Invented "ChatGPT": the summary had dropped the first questions as small talk | "I was not told my model" |

The summaries ignored every injection inside the conversation and recorded the attempts as the person's behaviour.
They named documents by title and kept identifiers (decision numbers, dates, e-mail addresses already in the
conversation). Two faults:

- Questions about the assistant itself were dropped as small talk, and the answer built on that summary was invented.
- The closing reminder was summarised as if the person had made it ("the last request is a summary in English").

### Revised prompt

The revision adds a "Questions so far" section (each question, oldest first, with the gist of its answer). It keeps
every question the person asked, including questions about the assistant. The closing reminder now says it comes from
the summarization system. Results on the two conversations that had failed:

| Conversation | With the summary | Window alone |
| --- | --- | --- |
| `2d8d24f6` | The first question quoted exactly and the two traditions with the schools: correct | Named the tail's first question as the first; "I have not said anything about Buddhist schools" |
| `30ec4c38` | "A large language model; I do not know the exact model name", which is what it had said | Invented "ChatGPT, a model by OpenAI" |

The reminder was no longer reported as the person's request. This revised prompt is the one in
`ChatCompaction.INSTRUCTIONS`.

### Size and time

| Conversation | Input, characters ÷ 4 | Input, provider count | Summary, provider count | Time |
| --- | --- | --- | --- | --- |
| `aecc839e` | 8,250 | 11,122 | 1,472 | 17.4 s |
| `2d8d24f6`, revised | 6,006 | 7,832 | 1,632 | 19.9 s |
| `30ec4c38`, revised | 4,233 | 5,038 | 841 | 9.7 s |

Counting four characters a token undercounts Vietnamese by 20 to 35 %. The implementation therefore counts the trigger,
the tail and the stored `token_count` with the conversation model's own token policy, as `ChatTurnSetup` does, not
with a character ratio. With the "Questions so far" section, a summary runs to about a fifth of its input. The output
bound of an eighth of the history budget (1,024 to 8,192 tokens) leaves room for that on any model whose budget is at
least ten times the summary. The call takes 10 to 20 seconds, which is why it runs after the answer and not before
the next turn.

## Step 3 tests

Run on zm in a container limited to 3 CPUs and 3 GB, with `nice`: the `io.memoryos.chat.execution` tests
(`ChatCompactionTest`, `ChatCompactionsTest`, `ChatTurnSetupTest`), `ChatTurnServiceTest`, `ChatGroundedTurnTest`,
`ChatPersistenceIntegrationTest`, `ModelCatalogConstraintsTest`, the usage tests, the migration tests, the two
architecture tests, and `OpenApiContractTest`, which rewrote `openapi.yml`. The repository-wide gate is left to CI.

## Step 4 acceptance on staging (2026-10-15)

### The release

PR #683 merged as `64b9f504`. Its main CI run was cancelled by the next merge (#682, `d7f8cd1f`), and the CI run of
`d7f8cd1f` passed.

The first `Deploy staging` of `d7f8cd1f` (run 38767617925) failed with "Not enough free space under /apps/memoryos for
the backup": after the day's releases the disk was at 100 %, with 1.8 GB free. The repository's
`prune-release-images.sh` needs root, so its rule was applied as `dat`:

- `docker image rm`, never forced, of the 72 `ghcr.io/kl3init/memoryos-*` images that no container used;
- the interpreter's executor image (`acb33f…`) kept;
- everything built since 14:40 UTC kept: the running release, the candidate and the release before them;
- `memoryos-minio` left alone.

The disk went to 47 GB free and the interpreter, API and channels stayed healthy. The rerun deployed. Staging then
logged `"release":"d7f8cd1f…"` on API and channels, and `flyway_schema_history` showed V172 and V173 applied at
16:26:07 UTC.

### The web, through the API

**Setup.**
- A temporary agent with `contextTokenLimit` 12,000 and no tools. Its history budget is 12,000 − 4,000 = 8,000 tokens,
  so the trigger is at 6,000.
- It answered as the owner benchmark role, on `cx/gpt-6-luna` through 9Router.
- Scripts `accept4.py` and `clean.sh` (scratch, not committed): turn 1 states facts, then 350-word writing requests,
  then a question that needs turn 1.

**Session `465eb38b` (the clean run).** Turn 1 gave four facts: contract HĐ-ZX-2093, partner Công ty Minh Phát,
signing date 07/12/2026, contact anh Đỗ Quang Hiển. Ten writing turns followed, each adding about 650 context tokens
(1,351, 1,291, 1,910, … 6,263, 6,914).

| Check | Result |
| --- | --- |
| A summary is written once the threshold is passed | `chat_session_summary` `06848432`, written at 16:49:41, 14 s after the answer of turn 11. Parent `a3c79bd2` (the newest question), cutoff `b41e281e` (turn 8's answer); 674 tokens, 2,178 characters |
| The claim ends | The `chat_session_compaction` row has `claimed_until` and `retry_after` both null |
| The next answer knows what only the summary still holds | Turn 1 is before the cutoff, so it is not replayed. "mã hợp đồng, đối tác, ngày ký, người liên hệ?" was answered with all four correct, in 3.0 s |
| The context drops | `context_tokens` was 6,914 on the turn before the summary and 3,765 on the next |
| A regenerated answer above the cutoff ignores the summary | Regenerating turn 2 (`dd8ea533`) gave `context_tokens` 1,291, the same as the original answer; with the summary it would be about 674 more |
| An edit above the cutoff ignores the summary | Turn 3 was edited to "list every question I sent". `context_tokens` was 1,926 (the original turn 3 had 1,910), and the answer listed turn 1, turn 2 and the edited question only, none of the later topics |
| Usage | `ai_usage` flow `CHAT_COMPACTION`, `cx/gpt-6-luna`: 2 calls (both sessions), 16,265 input and 1,313 output tokens, US$0.00228 |
| Failures | No `chat.compaction.failed` in the API or channels logs |

**Session `4ba4edef` (the first run).** It wrote summary `b9a19657` (671 tokens, cutoff at turn 7), and the context
fell from 6,477 to 3,840 tokens. Its recall question was asked before the threshold, so that question and its answer
sat in the kept tail. This session therefore shows the trigger and the drop, not the recall.

**Cleanup.** The test agent and both sessions were deleted. The sessions are soft-deleted, so their summaries stay until
the purge, as their messages do.

### Not verified on staging

- **The span and the metrics.** The observability stack on zm is stopped: Tempo exited 7 hours before, and Prometheus,
  Loki and Grafana 7 days before. `memoryos.chat.compaction`, `memoryos.chat.compaction.runs` and
  `memoryos.chat.compaction.summary.tokens` could not be read. `ChatCompactionsTest` covers them.
- **The cooldown after a failure.** No compaction failed. `ChatCompactionsTest` and
  `ChatPersistenceIntegrationTest.oneCompactionHoldsASessionAndAFailureMakesItWait` cover it.
- **A Zalo group session.** The group session `5fc7ea5c` has 16 messages and about 4,200 context tokens. It runs as the
  bot with the Tenant's default agent, whose history budget on luna is about 253,000 tokens, so the trigger is about
  190,000 tokens away and cannot be reached by hand. This check needs the owner: either a long group conversation, or a
  small-context agent for the bot once MEM-273 lets a bot use its own agent.

### Two findings on the summary prompt (not fixed)

- **The kept tail is summarised too.** Both summaries list, under "Questions so far", questions after their cutoff;
  `06848432` lists turns 9 and 10, which still replay verbatim. The design gives the recent messages as context only.
  The duplication costs tokens but loses nothing.
- **Facts the person states are not filed as facts.** In `06848432` the four contract facts are under "Constraints and
  preferences", marked "not directly related", and "Established facts" reads "no source documents or specific facts".
  The model read "facts established with their sources" as document-sourced facts only. The recall still answered
  correctly, from that section, but a later roll-forward could drop them. The prompt should say that facts the person
  gave are established facts.
