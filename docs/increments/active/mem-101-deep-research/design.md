# MEM-101 — Deep research

Tracking: [MEM-101](https://linear.app/memory-os/issue/MEM-101) (`dathip04`). Depends on [MEM-100](../../completed/mem-100-agent-activity-timeline/design.md) (Done, PR #160), whose tool-neutral activity contract this increment extends.

## Outcome

A Deep research mode in Chat that behaves like Onyx: an optional clarification turn, a streamed research plan, an orchestrator that delegates up to three parallel research agents over internal Search and the Web, and a long final report with merged citations. Progress streams on the MEM-100 timeline and survives reload and tab close.

## Accepted decisions (owner, 2026-09-15)

- Mirror Onyx as closely as the Java stack allows. Departures below are recorded with their reason.
- Reference checkout: Onyx `160f9b143` (main, 2026-09-15).
- No survival of execution across API restart. Onyx loses in-flight research on restart as well. Reload, tab close and client disconnect are covered by the background run and stream replay; since [MEM-26](../mem-26-chat-stream-redis/design.md) the replay lives in Redis and survives the restart, and the browser resumes until the outcome instead of polling history.
- As Onyx: no plan approval or edit step, no time or cost estimate, the report renders inline as the answer.
- For all Chat turns, not only research mode, as Onyx: no total turn deadline (a renewed run lease detects process death), 60 s provider read gap, refresh-on-write stream replay, browser recovery while the run is RUNNING, and no citation count cap. See [Chat-wide timing](#chat-wide-timing-owner-2026-09-15-all-chat-as-onyx) and [Citations](#citations).
- Entry and administration as Onyx: a separate Deep research composer button and a tenant administrator switch, enabled by default. See [Entry and administrator setting](#entry-and-administrator-setting).
- One Linear issue: the Chat-wide timing and citation changes are delivered under MEM-101, before research mode.

## Reference behavior (Onyx `160f9b143`)

Paths: `backend/onyx/deep_research/dr_loop.py`, `deep_research/dr_mock_tools.py`, `deep_research/utils.py`, `tools/fake_tools/research_agent.py`, `prompts/deep_research/{orchestration_layer,research_agent,dr_tool_prompts}.py`, `chat/citation_utils.py` (`collapse_citations`), `chat/process_message.py` (entry, stop, persistence), `db/models.py` (`ToolCall`, `ChatMessage.is_clarification`), `server/query_and_chat/placement.py`, `server/settings/{models,store}.py`, `web/src/sections/input/AppInputBar.tsx`, `web/src/hooks/useDeepResearchToggle.ts`, `web/src/views/admin/ChatPreferencesPage.tsx`, `web/.../timeline/renderers/deepresearch/{DeepResearchPlanRenderer,ResearchAgentRenderer}.tsx`.

| Step | Observed behavior |
| --- | --- |
| Entry | Request flag `deep_research`. Rejected for projects and multi-model; model needs ≥50,000 input tokens. |
| Clarification | One inference with only `generate_plan`. No tool call → the text is a clarification (≤5 numbered questions), message `is_clarification=true`, turn ends. The next turn skips clarification when the previous assistant message was one. |
| Plan | One streamed inference without tools: ≤6 numbered steps, emitted as `DeepResearchPlanStart/Delta`. Not persisted. |
| Orchestrator | ≤8 cycles (4 for reasoning models), `tool_choice=REQUIRED`, `max_tokens=1024`. Tools `research_agent(task)`, `generate_report`, and `think_tool` for non-reasoning models only. Cycle count is formatted into the system prompt each cycle; cycle 1 adds `FIRST_CYCLE_REMINDER`. ≤3 parallel agents per cycle. After 30 min or on the last cycle the report is forced. A failed agent yields a synthetic failure tool response. |
| Research agent | Thread per agent. History starts with only the `task`. ≤8 cycles, `tool_choice=REQUIRED`, `max_tokens=1000`. Tools: internal search, `web_search`, `open_url`, `think_tool`, `generate_report`. Only the first tool type of a batch runs, sequentially. Open-URL reminder after a Web search with results. Report forced after 12 min; 30 min timeout returns a timeout message but cannot kill the thread. Intermediate report ≤10k tokens, citation markers kept. |
| Citation merge | `collapse_citations` renumbers each agent's markers by `document_id` into the turn mapping. |
| Final report | No tools, ≤20k tokens, all intermediate reports in history, plan in a reminder, only cited documents passed. |
| Persistence | `tool_call` tree (`parent_tool_call_id`, `turn_number`, `tab_index`, `reasoning_tokens`, arguments, response) written at turn end; Stop saves partial output. |
| Stream/UI | `Placement(turn_index, tab_index, sub_turn_index)`, `TopLevelBranching`, `ResearchAgentStart`, `IntermediateReportStart/Delta/CitedDocs`. Plan renderer, per-agent renderer with task, nested tool groups and intermediate report. |
| Setting and button | `Settings.deep_research_enabled` in the KV settings store, read as enabled when unset (`svcSS.ts:123`), switched on the admin Chat Preferences page. The composer shows a separate button outside projects when the setting is on and the agent has internal or Web search; it is disabled in multi-model mode and resets on session switch, agent change and reload. The backend does not check the setting. |

## Design

### Two control layers

| Layer | Owner | Mechanism |
| --- | --- | --- |
| Clarification → plan → orchestrator cycles → final report | MemoryOS code, ported from `dr_loop.py` | One inference per step through `LlmMessageStreamer.streamInference` over `ChatModelGuard` |
| Research agent loop | See [research agents](#research-agents) | Existing tools (`SearchTool`, `WebTools`) |

`ChatModelExecutor` states that MemoryOS owns no inference/tool loop. Research mode is the intentional exception that the [MEM-11 design](../../completed/mem-11-production-chat/design.md) reserved for deep research ("workflow riêng"). The spike verified that `streamInference` performs one inference, returns tool calls without executing them, and records usage through the guard.

### Orchestrator phases

- Prompts are ported verbatim from `orchestration_layer.py` and `research_agent.py` with the Onyx MIT notice (`ResearchPrompts`: the 21 evaluated Onyx strings, including `dr_tool_prompts.py` and `INTERNAL_SEARCH_GUIDANCE`, compared byte for byte on 2026-09-15), plus the tool definition descriptions of `dr_mock_tools.py`, `THINK_TOOL_RESPONSE_MESSAGE`, the agent timeout and failure messages and `TOOL_CALL_FAILURE_PROMPT` (13 more strings, compared the same way). Filling a template renames the Onyx tools MemoryOS names differently: `internal_search` becomes `search_knowledge` and `open_urls` becomes `open_url`; inserted plans and tasks are never re-read as templates. Reasoning variants and the 8/4 cycle cap follow `ModelSettings.Capabilities.reasoning`. The spike showed that a truncated prompt with a static cycle counter made `gpt-5-mini` call `research_agent` to "synthesize a report" instead of `generate_report`; the per-cycle counter, first-cycle reminder and `generate_report` conditions are load-bearing.
- `tool_choice=required` is set on orchestrator and agent inference requests (verified on Chat Completions, live).
- `ChatModelGuard` final-cycle policy strips tools and adds the Chat last-cycle reminder. Research guards use `cycles = maxCycles + 1` and an identity final request so MemoryOS, not the guard, forces `generate_report`, as Onyx does.
- Implemented guard support (2026-09-15), replacing the `cycles + 1` workaround: `researchPrompts()` sends research prompts unchanged (no `ChatPrompts.forInference` tool guidance, citation or last-cycle reminder, no final request rewrite, no `CHAT_LAST_CYCLE_TOOL_CALL`); `cycles` remains a hard `CHAT_CYCLE_LIMIT` bound. `toolChoice(...)` sets the request transform per phase: `ChatModelBinding.requiredTools` (OpenAI Chat Completions: `tool_choice=required` when the request has tools) on orchestrator and agent cycles, identity on clarification (`AUTO`) and tool-free plan/report inferences. Adapters without it leave requests unchanged.
- One `ChatAdmissionLedger` per turn is shared by every research guard, so parallel agents reserve against one token/cost allowance; Embabel stays the usage and cost ledger.
- Output limits: each phase sets the guard output reservation and the streamer `max_tokens` to the Onyx value capped by the model's configured maximum output (the OpenAI request policy already clamps). A model with a lower maximum output writes a shorter final report instead of being rejected; this cap is recorded as a departure.
- `finish_reason=length` with tool calls stays `CHAT_INCOMPLETE_RESPONSE` (reachable at the 1,024-token orchestrator limit). Onyx fails the same way when truncated tool arguments do not parse.
- Gap: the OpenAI Responses route (hosted Web search or reasoning summaries) sends no `tool_choice`; required tool choice there belongs to the `think_tool` streaming decision.
- Clarification is skipped when the previous assistant message has `is_clarification`. Research mode is rejected for Project chats and for models whose context window is below 50,000 tokens.

### Research agents

Baseline: run each agent through the existing Embabel `PromptRunner` tool loop, with one `ChatModelGuard` per agent sharing the turn `AgentProcess` (its invocation list is a `CopyOnWriteArrayList`, so concurrent recording is safe). The guard already rewrites each inference prompt (`ChatPrompts.forInference`); the research variant composes the Onyx per-cycle system prompt and open-URL reminder there, and ends the loop at 12 minutes so MemoryOS runs the intermediate-report inference.

Evidence gap: Embabel streaming does not call loop inspectors or transformers (MEM-11 probe), and Chat tool guidance for `search_knowledge`/`web_search`/`open_url` would be added to agent prompts. If the guard hook cannot express the per-cycle prompt, forced report and Onyx tool guidance, the fallback is the same `streamInference` loop as the orchestrator with direct `Tool.call`. The first implementation step decides with a fixture test.

Decision (2026-09-15): research agents use the same `streamInference` loop as the orchestrator, with direct `Tool.call`. Fixture P6 (`DeepResearchSpikeProbeTest.researchAgentLoopRunsDirectToolCallsAndWritesTheIntermediateReport`) drives one guarded inference per cycle with a per-cycle system prompt and `tool_choice=required`, applies Onyx's first-tool-type filter (showing the loop controls which calls run; the product keeps the mixed-batch departure below), treats `generate_report` as a loop signal without executing it, and writes the intermediate report as a tool-free inference over the kept history; the guard records every inference. The `PromptRunner` baseline is rejected on code evidence: its streaming loop executes every returned call, returns only text (no history for the intermediate report), cannot stop at the 12-minute force-report point, and `ChatModelGuard` composes a fixed `ChatPrompts.forInference` prompt. Research agents therefore reuse tool instances (`SearchTool`, `WebTools`, `FileReaderTool`) through `Tool.fromInstance` and call them directly.

Tools: Onyx allows `{internal search, web_search, open_url}` plus `think_tool`/`generate_report` (`dr_loop.py:255`; its TODO lists non-search tools as a future extension). MEM-110 `run_python` and image generation are excluded.

Attached files (owner, 2026-09-15): when the turn has attachments, research agents also get `search_files` and `read_file`, the agent prompt lists the attached file names and IDs, and the orchestrator prompt lists the file names so tasks can direct agents to them. Without attachments the tool set is Onyx's.

| Item | Content |
| --- | --- |
| Requirement | Research must use the files the user attached. |
| Reference behavior | Onyx inlines attached file text into chat history (`chat_utils.build_file_context`), so clarification, plan, orchestrator and final report read it; agents see only their task. Onyx `FileReaderTool` is available only with `DISABLE_VECTOR_DB` (`file_reader_tool.py:78`). |
| MemoryOS gap | Non-image attachments reach the model only through `search_files`/`read_file` (`ChatFileInputs` inlines images only). Copying Onyx's tool set would hide attachments from research entirely. |
| Chosen | Reuse the existing authorized, bounded file tools with evidence registration in research agents. |
| Cost | Tool-list departure; the orchestrator plans from file names, not content; extra tool cycles; agents may read the same file. |
| Rejected baseline | Inline file text into history as Onyx: a new Chat-wide mechanism, context overflow for large files, and agents still see only their task. |
| Revisit | If plans are weak without file content, add a bounded file preview to the orchestrator prompt. Not in scope. |

Departure: Onyx runs only the first tool type of a batch because its `Placement` cannot distinguish nested parallel calls. MemoryOS events carry real tool call IDs, so mixed batches run sequentially without that filter.

### Parallelism and cancellation

Agents of one cycle run through `SearchTasks` (≤3, below its 4-thread bound) under the turn's scope. Stop and cancellation stop every agent and close provider connections (spike: 3 connections closed 9 ms after cancel). This is stricter than Onyx, which cannot stop timed-out threads.

### Citations

Each agent registers evidence in its own `ChatEvidence` with markers kept; after the cycle, sources are merged into the turn evidence by source key and intermediate report markers are renumbered, as `collapse_citations` does. The final report receives only cited sources.

Source count (owner, 2026-09-15: as Onyx). Onyx has no citation count cap: `DynamicCitationProcessor` and `collapse_citations` number every cited document, and only per-tool results are bounded (`NUM_INTERNET_SEARCH_RESULTS` 10, `NUM_RETURNED_HITS` 50, `MAX_CHUNKS_FED_TO_CHAT` 25, `open_url` 15,000 characters per URL). MemoryOS caps every Chat turn at 24 sources in `ChatSource.citationId`, `ChatEvidence`, `ChatTurnService`, `ChatTurnPersistence`, `ChatActivity` and its recorder, the V34 `chat_message.sources` CHECK (≤24 entries, 131,072 bytes), the web schemas and OpenAPI. The count cap is removed for all Chat turns, so sources are bounded by tool result limits, cycles and budgets. Storage keeps a byte bound (or moves sources to rows), and citation-marker token estimates that assume `[24]` are widened.

### Persistence

- V66 (implemented): `chat_command.deep_research` in command identity (request field `deepResearch`, absent means false, as Onyx's `deep_research` flag); replay with a different mode conflicts, as `webSearch` does. The same migration adds the tenant `chat_settings` row (below).
- V67 (implemented 2026-09-15): `chat_message.is_clarification` and `research_plan` (≤ 100,000 characters, `NOT VALID` CHECK because every existing row is NULL), written by the terminal finish in the same statement as `sources` and `activity` and returned in history as `research { clarification, plan }`. The turn accumulates `research-plan` deltas; beyond the bound the rest is dropped without failing the turn. Persisting the plan departs from Onyx so reload shows it; editing it stays out of scope.
- V68 (implemented 2026-09-16): the research agent tree is `chat_message.research_agents` jsonb (≤ 64 agents, 4 MiB, assistant rows only), written by the terminal finish in the same statement as `sources`, `activity` and the plan, and returned in history as `research.agents`. Each agent keeps its cycle, tab, task, status, duration, intermediate report (≤ 100,000 characters, agent citation numbers), the mapping of those numbers to the message sources, and its own tool steps and reasoning as a MEM-100 `ChatActivity`. Agents still running at the outcome are recorded as failed, so Stop keeps partial research. Over the byte budget the latest agents lose their reports first. Departure from Onyx `tool_call` rows, and from the earlier `chat_tool_call` table plan: history reads the tree only per message, as `activity`, so a column avoids a join and a second writer; orchestrator-level agent steps also stay in `activity`.
- The administrator setting is tenant-owned Chat configuration next to the existing Web and image connection defaults: `chat_settings(tenant_id, deep_research_enabled DEFAULT TRUE, revision)` in V66; no row means enabled. Clarification and tool-call schema are added with the code that writes them, so no unused columns ship ahead of it. (V64/V65 relax the Chat-wide source count cap.)

### Entry and administrator setting

Owner, 2026-09-15: as Onyx.

- Administrator switch: a tenant `deepResearchEnabled` Chat setting, enabled when unset, with the Onyx title "Deep Research" and description ("Agentic research system that works across the web and connected sources. Uses significantly more tokens per query."). MemoryOS has no Chat Preferences page; the switch lives in Chat administration under the existing model-management authority, beside the Web search settings. Members read availability, as for Web search.
- Composer button: a separate Deep research button in the composer action row, not a `+` menu item. It shows when the chat is not in a Project, the setting is on, and internal Search or Web search is available for the turn. The selection is browser state for the current chat: it resets when switching to another existing chat or reloading, and survives creating a new chat from the empty composer, as `useDeepResearchToggle` does. MemoryOS has no multi-model or agent switch, so those Onyx conditions have no counterpart.
- Departure: Onyx only hides the button; MemoryOS also rejects research commands while the setting is off, because a disabled mode must not run through the public API.

### Streaming, events and UI

- `ChatToolEvent` gains `parentToolCallId` and `tabIndex`. New events: `research_plan` (delta), `research_agent_start`, `intermediate_report` (delta, cited sources), `top_level_branching`. SSE and OpenAPI follow the MEM-100 rename rules.
- Implemented (2026-09-15, `ChatResearchEvent`). Wire names follow the existing kebab-case SSE names: `research-plan` (delta), `top-level-branching` (`branches` 2–3), `research-agent-start` (`toolCallId`, `tabIndex`, `task` ≤ 4,000 characters), `intermediate-report` (delta per agent call) and `intermediate-report-citations`. The research agent call's own `tool` events carry `tabIndex` (0–2); every step of that agent, and its `reasoning`, carries `parentToolCallId`. Plan, report and reasoning deltas are chunked like answer text, with one pending chunk per agent, so interleaved agents never merge text.
- Citations on the wire: an intermediate report streams with the agent's own citation numbers, as Onyx `KEEP_MARKERS`. Instead of Onyx `IntermediateReportCitedDocs` (full documents per agent), `intermediate-report-citations` maps each agent number to the turn source it was merged into; the merged sources arrive as ordinary `SOURCE` tool events of the agent call, so the turn keeps one source sequence. Agent `SOURCE` events with local numbers are not streamed. Nested steps, nested reasoning and research events bypass the top-level `activity` recorder and the turn source sequence; they are persisted in the tool call tree.
- Old clients skip the new event types (the transport ignores unknown types; committed history stays authoritative).
- UI reuses the assistant-ui `activity-group` timeline: plan block, one tab per parallel agent with task, nested tool steps and expandable intermediate report, then the final report as the answer. Onyx renderers are the behavior reference. Entry is described in [Entry and administrator setting](#entry-and-administrator-setting).

### think_tool reasoning streaming

Spring AI aggregates tool-call chunks before MemoryOS sees them (spike P4), so on Chat Completions `think_tool` reasoning appears as a whole paragraph at cycle end. The OpenAI Responses stream delivers the arguments incrementally (spike L2: 214 deltas, first after 3.8 s, concatenation equal to the final arguments), and `OpenAiResponsesChatModel.StreamState` is MemoryOS code that sees every event.

Decision to take in implementation, per the conventions' departure record:

| Item | Content |
| --- | --- |
| Requirement | Onyx streams `think_tool` reasoning live for non-reasoning models. |
| Proposed | Route research inferences on Responses-capable OpenAI connections through `OpenAiResponsesChatModel`; map `functionCallArgumentsDelta` of `think_tool` items to `ChatReasoningDelta` with the Onyx JSON-prefix stripping; add `tool_choice` to Responses requests (currently absent). |
| Cost | Responses routing independent of `webSearch`/`reasoningSummary`, plus fixture tests. OpenAI-compatible endpoints without Responses keep the baseline. |
| Simpler baseline | Paragraph at cycle end. Reasoning models are unaffected because they use MEM-100 reasoning summaries. |

### Limits

A `memoryos.chat.research.*` block holds the Onyx values: orchestrator force-report 30 min, agent force-report 12 min and timeout 30 min, orchestrator `max_tokens` 1024, agent 1000, intermediate report 10k, final report 20k, ≥50k context, ≤3 agents, cycles 8/4 and 8. Onyx has no total turn deadline; research phases enforce these limits themselves. Research turns use the same run lease as all Chat turns (see [Chat-wide timing](#chat-wide-timing-owner-2026-09-15-all-chat-as-onyx)), so no separate research deadline exists.

### Chat-wide timing (owner, 2026-09-15: all Chat, as Onyx)

Compared on 2026-09-15 against Onyx `160f9b143`:

| Item | Onyx | MemoryOS before this increment | Change |
| --- | --- | --- | --- |
| Turn deadline | None. A processing fence (`chat_processing_checker.py`, TTL 30 min) is refreshed every 60 s while the writer lives (`process_message.py:1492`); a lapsed fence marks a dead run, resume ends and the client renders the persisted message (`chat_backend.py` resume-stream) | `deadline_at = reservation + memoryos.chat.execution.deadline` (2 min, capped at 30 min); `expireRuns` fails RUNNING rows 5 s past it; tools and the runner derive remaining time from it (`SearchTool`, `WebTools`, `FileReaderTool`, `GenerateImageTool`, `ChatModelExecutor`) | `deadline_at` becomes a lease renewed every 60 s to now + 30 min while the run lives; `expireRuns` reconciles only lapsed leases (process death). No total turn bound: cycles, token/cost budgets and per-call timeouts bound work; tools use their own timeouts instead of the turn remainder. Every blocking call must keep an own timeout |
| Provider call timeout | 60 s gap between packets (`LLM_SOCKET_READ_TIMEOUT`, `DR_REPORT_LLM_TIMEOUT_S`); no total bound | Total call timeout equals the turn deadline (`ChatModelResolver` → `OpenAiCancellation` `callTimeout`; read/write default to it) | Read/write gap 60 s; no total bound beyond cancellation, Stop and the run lease |
| Stream replay | TTL 3600 s refreshed per write; 600 s after completion; 16 MiB (`CHAT_STREAM_BUFFER_*`) | Each chunk expires 10 min after creation (`StreamBufferWriter.expire`); 4 MiB per run | Refresh-on-write TTL, completion retention and byte cap as Onyx; the process total bounds held bytes, not reservations (below) |
| Browser recovery | Resume from cursor without a time cap (`useChatSessionController`) | 3 × 65 s SSE attempts, then history polling for at most 31 min (`chat-transport.ts`) | Resume from the cursor while the stream keeps producing events; three silent connections, a `reset` or a sequence gap fall back to polling while the run is RUNNING, and a lapsed lease ends it through reconciliation. The first delivery kept the three-attempt cap, so research turns longer than about three minutes polled without progress; found on staging and fixed in PR #206 |
| Heartbeat | 15 s | 15 s | None |
| First-chunk retry | 2 (`LLM_FIRST_CHUNK_MAX_RETRIES`) | 0 (MEM-11) | None; not part of this decision |
| Stop | Cache fence; running threads continue | Local cancellation closes provider connections | None; MemoryOS keeps stronger cancellation |

These changes apply to every Chat turn and are delivered and re-verified (Stop, cancellation, reconnect, replay, process-death reconciliation) before research mode depends on them.

Implementation decisions (2026-09-15):

- Configuration: `memoryos.chat.execution.deadline` is replaced by `lease-ttl` (30 min), `lease-renewal` (60 s) and `provider-read-timeout` (60 s). No deployment override of the old key exists. The `deadline_at` column keeps its name and now stores the lease expiry.
- Renewal runs in the existing one-second maintenance tick, only for runs whose last renewal is at least `lease-renewal` old, in one `UPDATE … RETURNING id`. A failed renewal is logged and retried; it never stops the run (Onyx never kills a run over a fence refresh). A run whose row is no longer RUNNING is interrupted locally.
- The terminal write no longer turns a past `deadline_at` into `CHAT_DEADLINE`: a live process finishing proves it was alive. Only reconciliation fails a row, and the `status = 'RUNNING'` predicate then rejects the late write. `CHAT_DEADLINE` is no longer produced.
- `TurnContext`/`ChatTurnSetup` carry no deadline. Own bounds: helper calls `helper-timeout` (60 s); Web batches 30 s; file reads, file search and attachment materialization 60 s; image calls the image client's 5 s connect and 120 s response timeouts; the final answer stream has no total (`blockLast()`), bounded by the read gap, Stop and the lease.
- The provider `Timeout` sets connect, read and write to `provider-read-timeout` and `request` to zero (no total); per-request helper timeouts still rebuild the OkHttp call with their own total. Model listing (`reportedModels`) uses the same value as its connect and request bound.
- Superseded by [MEM-26](../mem-26-chat-stream-redis/design.md) (2026-09-17): the replay now lives in Redis without a process total, and past its reply bound it is truncated as Onyx. Original decision: stream replay, a departure in accounting only: Onyx stores the buffer compressed in Redis without a process total. MemoryOS holds it in heap, so `total-bytes` (64 MiB) bounds bytes actually held instead of reserving `run-bytes` per open buffer. With a 16 MiB run cap the old reservation would have admitted only four buffers, below `concurrency` 8. When held bytes exceed the total, completed buffers are evicted first, then the writing run loses its oldest events as a gap; up to `max-streams` buffers stay open. Over its run cap, Onyx marks the buffer truncated and stops appending; MemoryOS keeps its existing drop-oldest gap, so a reader that falls behind still reaches the terminal outcome.
- Owner decision (2026-09-15), a departure from Onyx: API startup fails every RUNNING row with `CHAT_INTERRUPTED` before the web server accepts requests, so a hard-killed process does not leave sessions blocked for the 30 min lease once the API is back. Onyx waits for its fence TTL. It relies on the single-replica deployment: several API replicas or an overlapping rolling deploy would fail another process's live runs, so that change must remove it. A failed startup pass is logged and lease reconciliation remains the fallback.

### Observability

Spans per phase (`clarification_step`, `research_plan_step`, `research_execution_step`, `research_agent`, `generate_intermediate_report`, `generate_report`) and bounded metrics for cycles, agents, timeouts and forced reports. No question, task, plan or document content in telemetry.

Implemented in `ResearchTelemetry`:

| Signal | Kind | Labels (bounded vocabulary) | Semantics |
| --- | --- | --- | --- |
| `memoryos.chat.research.phase` | Observation span | `phase` (the six names above), `outcome` (`completed`, `failed`, `canceled`) | One span per phase run; agent spans are parented on the execution step explicitly because agents run on other threads. Errors carry the exception type only. |
| `memoryos.chat.research.phase.duration` | Timer | `phase`, `outcome` | Monotonic time from phase start to return or throw; phases are never retried. |
| `memoryos.chat.research.cycles` | Distribution summary | none | Orchestrator inferences run in one turn, recorded when the execution step ends, including on failure. |
| `memoryos.chat.research.agents` | Counter | `outcome` (`completed`, `failed`, `timeout`) | Once per agent; a Stop counts nothing. |
| `memoryos.chat.research.forced.reports` | Counter | `scope` (`orchestrator`, `agent`), `reason` (`time`, `cycles`) | A report forced by the elapsed-time bound or the last cycle. |

## Out of scope

Survival of execution across restart, tools other than internal search, Web/URL reading and attached-file reading (`run_python`, coding agent), user-provided prompts, plan approval or editing, report export, Project chats, multi-model research.

## Departures from Onyx

| Onyx | MemoryOS | Reason |
| --- | --- | --- |
| Timed-out agent threads keep running | Stop and cancellation stop agents | Existing cancellation contract; avoids unbounded cost |
| Plan not persisted | Plan persisted | Reload shows progress |
| Attached file text inlined into history; agents have no file tools | Agents get `search_files`/`read_file` when the turn has attachments | MemoryOS attachments reach the model only through these tools |
| First tool type per agent batch | Mixed batches, sequential | Onyx workaround for `Placement`; MemoryOS has tool call IDs |
| Setting only hides the button | Server also rejects research commands while disabled | A disabled mode must not run through the API |
| No budget | One token/cost admission ledger shared by all research guards of a turn | Existing Chat limits; parallel agents must not each spend the whole budget |
| Final report `max_tokens` 20,000 regardless of model | Phase limits capped by the model's configured maximum output | The request policy already clamps to the configured maximum; rejecting such models would hide research entirely |
| Research agents receive the Persona's tools without a per-turn Web toggle | Agents get Web tools only when the turn has Web search on and an external search connection | Web access follows the existing per-turn Web toggle and connection authorization; the composer button does not depend on Web availability |
| Research input limit is the model window minus the report tokens | Same; the Chat `context-token-limit` and Persona context limit bound only normal answers and history selection | Mirrors Onyx; research prompts carry agent reports that exceed the normal-answer limit |
| Clarification text streams | Clarification text is emitted once the inference ends | The same inference may instead call `generate_plan`, and text before that call must not become the answer; bounded by the answer token limit |

## UI comparison with Onyx (2026-09-16)

The Onyx renderers at `160f9b143` were read directly: `DeepResearchPlanRenderer.tsx`, `ResearchAgentRenderer.tsx`, `ParallelTimelineTabs.tsx`, `headers/ParallelStreamingHeader.tsx` and `useDeepResearchToggle.ts`. Onyx also renders parallel agents as tabs, so the MemoryOS tab layout matches its shape. These affordances are not implemented here:

| Onyx | MemoryOS today | Note |
| --- | --- | --- |
| Tab triggers show the tool icon, the tool name and a per-tab loading state | Plain text "Tác tử {{n}}" | `ParallelTimelineTabs` uses `getToolIcon`/`getToolName` and `isLoading` |
| Tab list has scroll arrows and an expand/collapse button on the right | `overflow-x-auto` only | Narrow screens have no affordance beyond dragging |
| A branch icon marks the parallel group row | No marker | The `top-level-branching` event arrives but changes nothing visually |
| Plan and intermediate report use `ExpandableTextDisplay`: collapsed preview (report `maxLines` 5), expand, streaming state | Plan always full; report in a `<details>` | Long plans and reports push the answer down |
| Status text pairs per phase ("Generating research plan" then "Generated"), circle then check icon | One fixed step title with running/done state | |
| COMPACT and HIGHLIGHT render modes show the latest active item as a live preview while collapsed | Collapsed shows only a summary label | Onyx keeps a sense of progress without expanding |
| `useDeepResearchToggle` resets the toggle when the assistant changes, as well as between existing sessions | Resets per session and reload only | Persona change keeps the toggle on here |
| Agent duration is not shown in either | `durationMs` is persisted and streamed but never rendered | MEM-100 timeline does show a spoken duration |

Patterns from eight shipped assistants are compared in [ux-research.md](ux-research.md) (Mobbin screenshots, 2026-09-16).

## Prompt fidelity check (2026-09-16)

The Onyx commit `160f9b143` was fetched and every `ResearchPrompts` constant compared with `orchestration_layer.py`, `research_agent.py`, `dr_tool_prompts.py` and `dr_mock_tools.py` by a script (scratchpad `compare_prompts.py`), after applying Java text-block rules, Python line joining, Onyx f-string tool names and the MemoryOS tool renames. Result: 19 shared constants, 17 identical byte for byte; the two agent prompts differ only in the placeholder name (`{MAX_RESEARCH_CYCLES}` against `{max_research_cycles}`). `INTERNAL_SEARCH_GUIDANCE` has no Onyx counterpart under that name.

The check found one real defect, fixed: the agent prompt hard-coded "cycle X of 8" while `agent-cycles` is configurable (2-20). It now fills `{max_research_cycles}` from the limit, as Onyx interpolates `MAX_RESEARCH_CYCLES`. A second suspected defect was not real: `ResearchPrompts.fill` ends in `text(...)`, so Onyx tool names are renamed across the composed prompt; `ChatSessionApiIntegrationTest` now asserts that for an agent prompt as well.

The limits were checked against the same commit: `MAX_ORCHESTRATOR_CYCLES` 8 and 4 for reasoning, `MAX_RESEARCH_CYCLES` 8 with the loop condition `while cycle <= MAX`, `DEEP_RESEARCH_FORCE_REPORT_SECONDS` 30 minutes, 12 minutes before a forced intermediate report, a 30-minute agent timeout, `MAX_FINAL_REPORT_TOKENS` 20,000, `MAX_INTERMEDIATE_REPORT_LENGTH_TOKENS` 10,000, orchestrator `max_tokens` 1,024, agent `max_tokens` 1,000 and `MAX_USER_MESSAGES_FOR_CONTEXT` 5. Every value matches `ResearchProperties`.

## Spike evidence (2026-09-15)

`api/src/test/java/io/memoryos/api/chat/DeepResearchSpikeProbeTest.java`, opt-in via `MEMORYOS_DR_SPIKE=true`; live probes also need `MEMORYOS_DR_SPIKE_LIVE=true` and `SPRING_AI_OPENAI_API_KEY`. All four passed. Fixture probes use a local OpenAI-compatible SSE server.

| Probe | Result |
| --- | --- |
| P1 one orchestrator inference | `streamInference` streamed pre-tool text, returned 3 `research_agent` calls, executed no tool, recorded usage once through `ChatModelGuard`. |
| P2 request | `tool_choice: "required"`, 3 tools, `stream: true`; `parallel_tool_calls` omitted (provider default). |
| P3 second cycle | History serialized as `system, user, assistant(3 tool_calls), tool×3` with matching IDs; model returned `generate_report`. |
| P4 raw chunks | Tool calls reach MemoryOS only fully aggregated, once per inference. |
| P5 parallel Stop | Three concurrent streams under `SearchTasks`; `scope.cancel()` closed all three connections in 9 ms and drained the scope. |
| L1 live `gpt-5-mini`, Chat Completions | Cycle 1 (8.9 s): 3 parallel self-contained `research_agent` tasks. Cycle 2 (2.7 s) accepted the tool history but called `research_agent` to synthesize instead of `generate_report` under the truncated spike prompt. |
| L2 live `gpt-4.1-mini`, Responses SDK | `think_tool` arguments arrived as 214 deltas between 3.8 s and 6.5 s; joined deltas equal the final 1,261-character arguments. |

Not proven: parallel Embabel tool loops per agent, the Responses product route for research, full Onyx prompts on a real model, usage after Stop (unknown, as in Chat), and staging corpus acceptance.
