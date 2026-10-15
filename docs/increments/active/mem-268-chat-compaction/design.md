# MEM-268 Chat compaction

A long conversation keeps its earlier turns in what the model knows: when the history a turn would send nears the
model's input limit, the older turns are summarised once and the summary stands in for them, instead of being dropped
without a trace as today. One step in the chat core serves every surface: the web, Lark and Zalo direct messages, and
the group sessions of [MEM-263](../mem-263-zalo-bot/design.md).

## Today

`ChatTurnSetup.resolve` walks the selected branch from the newest message backwards, counting tokens, and stops at
the first message that does not fit the history budget (`historyLimit`, reduced by min(4096, limit / 3) for a
tool-capable turn). Older turns are dropped and the model is not told they existed. History replays user and
assistant text, artifact JSON and inlined file text; earlier tool calls are never replayed. The full transcript stays
in PostgreSQL. A question that alone exceeds the limit fails; an overflow inside one turn's tool loop fails with
`CONTEXT_LIMIT`.

Channel turns and web turns share this path: the web through `ChatTurnController` → `ChatTurnService.command`, a
channel through `ChannelInboundService` → `ChannelTurns.ask` → `ChatTurnService.command`; both reach
`ChatTurnSetup.resolve`.

Embabel 1.5.2 has no compaction: `ToolLoopTransformer` is a hook inside one tool loop, and its implementations
(`SlidingWindowTransformer`, a message-count window; `ToolResultTruncatingTransformer`) only cut. Nothing in it
summarises across turns or keeps a summary.

## How the references do it (code read 2026-10-15)

| | Trigger | Kept verbatim | Summary kept in | Model | On failure | Shown |
| --- | --- | --- | --- | --- | --- | --- |
| Onyx | After the answer is saved, when the history the next turn replays exceeds 75 % of the window left | ~20 % newest, cut just before a USER message | A hidden `SUMMARY` message on the last USER message, with its cutoff; branch-aware | The conversation's | Logged; turn stays uncompacted | No |
| OpenClaw | Before inference near the limit; on a provider overflow (compact and retry); `/compact` | ~20k tokens, never splitting a tool call from its result | A `compaction` transcript entry with the first kept entry | The session's or a configured one | 3 retries, then commit without summary | Opt-in notices |
| GoClaw | 70 %: trim old tool results; 85–100 %: summarise, one per session (lock) | Last 4 or 30 % | `sessions.summary`, rolled forward | The agent's | Keep the messages | Token counts |
| Hermes | 50–75 % of the window; `/compress` | First 3 + ~20 %, the last user turn always | In place, old rows archived | A cheap auxiliary model | A fixed fallback, with a cooldown | Status line, gauge |
| spring-agent | None; 200 messages kept | | | | | |

All four that compact keep a token-budgeted verbatim tail, update the previous summary instead of rewriting it, keep
the full transcript, and run one session engine for every surface (OpenClaw: a Zalo message reaches
`runSessionCompactionIfNeeded` by the same path as web chat; GoClaw: `loop.Run`; Hermes: `run_conversation`; Onyx:
its Slack bot calls the same chat engine). They disagree on when it runs, which model writes it, whether the user sees
it, and what failure does; the choices below take a side on each.

## Decisions

1. **After the turn, off the answer's path.** As Onyx, compaction runs once the assistant message is saved, in the
   process that ran the turn, on a virtual thread that holds neither the stream nor the turn's slot or lease. One
   compaction a session at a time, by a claim in the database (GoClaw's lock, Onyx's claim). A turn that starts before
   the summary exists uses the previous summary and the sliding window; nothing waits for it.
2. **Trigger.** The tokens the next turn would replay — the current summary plus the messages after its cutoff,
   counted with the turn's own token policy as `ChatTurnSetup` counts them — exceed 75 % of the history budget of the
   conversation's model. Measuring what is replayed, not the whole branch, keeps it from firing again on every turn
   (Onyx). No message-count trigger and no command in this increment.
3. **Kept tail.** The newest messages up to 25 % of the history budget stay verbatim, cut only just before a USER
   message, and the last question with its answer is always kept whole (Onyx, Hermes). The system message and the
   workspace files are rebuilt on every turn and never summarised.
4. **Bulky content first.** Before anything is summarised, older turns drop their inlined file text and artifact JSON
   and keep the file's metadata line, from which `read_file` still reaches the file (GoClaw trims tool results first,
   Hermes shortens tool bodies first). This happens in `ChatTurnSetup` whether or not a summary exists, and is often
   enough on its own.
5. **Kept by branch.** A new table `chat_session_summary` (`session_id`, `parent_message_id` = the last USER message
   it was written after, `cutoff_message_id` = the last message it covers, `content`, `token_count`,
   `model_configuration_id`, `created_at`), in `chat.session.persistence`; the claim and the cooldown are one
   `chat_session_compaction` row a session (`claimed_until`, `retry_after`), apart from the summaries, which are per
   branch, and from `chat_session`, whose row every turn updates. A turn uses the summary whose parent is the
   nearest ancestor on its selected branch and whose cutoff is on that branch; any other is ignored, so an edit or a
   regeneration above the cutoff falls back by itself (Onyx `load_branch_summary`). Not a message role: a role would
   reach the history API, sibling counts and the edit and regenerate paths. The transcript is unchanged.
6. **How a turn uses it.** `ChatTurnSetup.resolve` places the summary right after the system and workspace messages,
   as a user message labelled as a summary of the earlier conversation (data, not instructions), then replays only
   the messages after its cutoff. The sliding window stays as the guard inside the budget: the summary is always kept,
   and the tail is trimmed if it still does not fit.
7. **Model.** A new task `ModelFlow.CHAT_COMPACTION`, a Tenant task model that falls back to the conversation model
   through `ChatModelAccess.selectFlow` as `CHAT_NAMING` does, reasoning `OFF` by default, called through
   `ModelCalls`; its usage is recorded as part of the conversation's Chat cost. An administrator can pick a cheaper
   model (Hermes); the default matches Onyx and OpenClaw.
8. **Prompt.** Onyx's shape: the older messages, a cutoff marker, then the recent messages as context only. OpenClaw's
   and Hermes's checkpoint sections, for a knowledge assistant: what the person is trying to do, their constraints and
   preferences, facts established with their sources by document title (never citation numbers), decisions, open
   questions. Written in the conversation's language, identifiers kept exactly (OpenClaw), the transcript treated as
   data (Hermes), the previous summary updated when there is one. It is given exactly what `ChatTurnSetup` would
   replay, so a question the guardrail hid stays hidden.
9. **Failure.** Logged as `chat.compaction.failed` with `error_type` and `error_code`; nothing is written and the
   sliding window goes on as today (Onyx, GoClaw). A session whose compaction failed is not tried again for ten
   minutes (Hermes's cooldown). No fixed fallback summary (Hermes) and no summary-less compaction (OpenClaw): a summary
   that says nothing true misleads the model more than the window does.
10. **Not shown.** The transcript on the web is unchanged and no notice is streamed or sent to a channel (Onyx). The
    context ring already shows how full the context is.
11. **Spans and metrics.** `memoryos.chat.compaction` span; `memoryos.chat.compaction.runs` by outcome and the summary's
    tokens, following the observability guideline.

## Not in scope

- Trimming inside one turn's tool loop (the `CONTEXT_LIMIT` path): that is Embabel's `ToolLoopTransformer` hook and
  [MEM-258](https://linear.app/memory-os/issue/MEM-258).
- Writing durable facts to the member's memory (MEM-250 `update_memory`) before compacting, as OpenClaw flushes
  memory first: a later step.
- A `/compact` command, a visible divider at the cutoff, compaction of Deep research turns.

## Risks

- A summary loses detail. The kept tail, the exact identifiers and the update-not-rewrite rule bound it; the full
  transcript stays readable on the web.
- Cost: one extra model call each time the threshold is crossed, on the task model.
- Two turns of one conversation can run close together (a group session); the claim makes only one compact, and the
  other uses what exists.
