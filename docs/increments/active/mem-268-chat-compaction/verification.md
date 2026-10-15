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
