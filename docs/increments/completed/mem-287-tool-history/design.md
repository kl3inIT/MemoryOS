# MEM-287: earlier turns' tool calls and results in the history

## Problem

A Chat turn's history holds earlier questions and answers only. The tool calls of those turns, and what they returned,
are gone: `chat_message.activity` keeps a step's name, status and citations, never its arguments or result
([`ChatToolEvent`](../../../../memoryos-core/src/main/java/io/memoryos/chat/ChatToolEvent.java): "tool arguments and raw
results are not streamed"). On staging on 2026-10-16 ([MEM-270](../mem-270-chat-sandbox/design.md)), turn 1 cleaned a
workbook into `/workspace/doanh_thu_2026_da_lam_sach.xlsx`; turn 2 ("add a column chart and send me the cleaned
workbook") listed only `/workspace/attachments` and answered that it could not create the file. Answers may not name
sandbox paths, so nothing in the history said where the file was. A prompt rule pointing at `/workspace` (PR #714) was
closed in favour of this change.

## What the references do

Read in code or official documentation on 2026-10-16.

| Product | Turn N+1 sees of earlier tool work | Source |
| --- | --- | --- |
| Dust | Calls and results; near the context limit old results become "This tool result is no longer available (pruned to prevent context window overflow)." in batches of 20,000 tokens | `front/lib/api/assistant/conversation_rendering/pruning.ts` |
| LibreChat | Calls and results as `ToolMessage`; optional pruning of old results to "[Old tool result content cleared]" | `danny-avila/agents` `src/messages/format.ts`, `contextPruningSettings.ts` |
| OpenHands | Every action and observation; past 240 events the first half becomes one summary | `software-agent-sdk` `llm_summarizing_condenser.py` |
| Onyx Craft | The sandbox agent's own opencode session keeps calls and results; compaction cuts long outputs to 1,250 characters | Onyx `craft-sessions.md`; opencode `session/compaction.ts` |
| Anthropic | The client resends calls and results; context editing replaces old results with a placeholder past about 100k tokens, keeps the last 3 and keeps the calls | platform.claude.com, "Context editing" (`clear_tool_uses_20250919`) |
| Open WebUI | Earlier code and its output always go back, "so the LLM knows the code was already executed and doesn't retry" | `backend/open_webui/utils/misc.py` |
| Onyx chat | Calls with their arguments; results replaced by "This tool call completed but the results are no longer accessible." | `backend/onyx/chat/chat_utils.py` `convert_chat_history` |

Every product with a sandbox replays calls and results, and under context pressure drops old results before calls. None
lists the sandbox's files in the prompt.

## Design

As Dust and Anthropic, with one restriction for organization knowledge.

- **Capture.** `ChatToolActivity`, already the runner's tool-call inspector, keeps each call's provider id, name,
  arguments and `resultAsString` from Embabel's `AfterToolCallContext`, in call order. Quiet calls (an MCP server's
  opening tool) are not kept, as they are no step. Deep research runs its own loop and keeps nothing; its turns replay
  as today.
- **What is kept.** The result only of tools whose output is the turn's own work or public: `bash`, `bash_output`,
  `edit_file`, `publish_file`, `run_python`, `web_search`, `open_url`, `generate_image`, `edit_image`, and only until
  the answer first calls `search_knowledge` or an MCP tool: after that, a script's output may print what they brought in
  (`run_python` also receives the found documents' files), so no later result is kept. Every other result is replayed
  as "This tool call completed but its result is not kept.": replaying stored organization or personal content would
  outlive a revoked permission, a deleted file or a forgotten memory. Arguments are kept for those tools and for
  `search_knowledge`, `read_file` and `search_files`, which name only queries and files; `update_memory` (the memory's
  text) and MCP tools (any payload) keep a note instead. This is the one departure from Dust, which keeps every result
  and argument. Files the person attached stay readable: they belong to the conversation and go with it.
- **Bounds.** Arguments and a result are each stored up to 64 KiB (the tools already bound their output to 30,000 to
  50,000 characters); a call's result past that is cut with a marker. A message keeps at most 256 KiB of calls in total;
  past it the remaining results are not kept.
- **Storage.** `chat_message_tool_calls` (V182): tenant, assistant message, position, provider call id, tool name,
  arguments, result (null when not kept). Written in the transaction that finishes the turn, whatever its status, since a
  cancelled turn may already have written files. Deleted with its message.
- **Replay.** The history walk in `ChatTurnSetup` places, before each earlier answer, one assistant message with that
  call (`AssistantMessageWithToolCalls`) and its result (`ToolResultMessage`) for every kept call, in order, as Deep
  research already builds its history. Branches need nothing new: the walk follows `parent_message_id`, and the calls
  belong to their answer.
- **Pruning.** The walk goes newest first under the existing history budget. A turn is first tried with its results;
  if it does not fit, with every result replaced by "This tool result is no longer available (pruned to fit the
  context).", as Dust and Anthropic replace results before calls; if that still does not fit, with its answer only; then
  the walk stops as today. The newest turns keep their results, older ones keep their calls.
- **Where tool messages cannot go.** Only a model that calls tools gets them, and the request policies already reject
  tool messages for other models. Calls that send the history to a model without tools strip them: the search rewrite
  (`SearchTool`) and a turn that registers no tool. Anthropic refuses `tool_use` blocks in a request without tools.

## Out of scope

Summarising old tool work (OpenHands, opencode): MEM-268 compaction already summarises turns before its cutoff, and those
turns' calls go with them. Replaying the model's reasoning or text written between calls. Deep research steps.
