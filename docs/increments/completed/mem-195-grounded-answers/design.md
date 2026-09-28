# MEM-195 — Grounded-only answers and topic guardrails

Status: design agreed with the owner on 2026-09-26; implemented on the branch (see "As implemented"); staging acceptance open. Flow diagram: [diagrams/grounded-flow.html](diagrams/grounded-flow.html).

## The failure

At the Tasco "Demo AI IN OFFICE" meeting on 2026-09-24 (staging meeting `38d29614-…`, 10:27–13:24), two questions that no Tenant document answers got answers from the model's own knowledge, with no citation: "Việt Nam có bao nhiêu tỉnh?" and "Vợ bác Hồ là ai?". Tasco's verdict: an assistant that does this cannot be published.

Why it happens today:

- **The model decides whether to search.** `ChatPrompts` makes the model search only when the user explicitly asks for internal, connected or named documents ([Chat spec, Grounded Chat](../../../specs/chat.md#grounded-chat--phase-31)). A general question is not such a request, so the model answers without calling `search_knowledge`.
- **Nothing on the server checks the answer.**
  - `[n]` markers are resolved only by the browser.
  - The persisted `sources` are every registered source, cited or not.
  - Text streams to the browser chunk by chunk (`ChatTurnService.execute`) before anything could check it.
- **Retrieval scores never reach Chat.** `DocumentSearchService` replaces the OpenSearch score with a weighted RRF score, and there is no reranker. Relevance is decided by the LLM selection step in `SearchTool`.
- **No topic moderation exists.**

## References

| Concern | Reference | What MemoryOS takes |
| --- | --- | --- |
| Product model | [Amazon Q Business guardrails](https://docs.aws.amazon.com/amazonq/latest/qbusiness-ug/guardrails.html) | See below. |
| Streaming a checked answer | [Bedrock Guardrails streaming](https://docs.aws.amazon.com/bedrock/latest/userguide/guardrails-streaming.html), [Azure OpenAI content streaming](https://learn.microsoft.com/en-us/azure/ai-foundry/openai/concepts/content-streaming), [NeMo output rail streaming](https://docs.nvidia.com/nemo/guardrails/configure-guardrails/yaml-schema/streaming/output-rail-streaming) | All three offer a checked mode (buffer, check, release; the default at Amazon and Microsoft) and a fast mode (stream, check later, may expose content). All check by chunk, not the whole answer. |
| Held streaming, in code | [NeMo Guardrails](https://github.com/NVIDIA/NeMo-Guardrails) `nemoguardrails/rails/llm/buffer.py` (`RollingBuffer`), `llmrails.py` (`stream_first`) | Chunks are checked before release when `stream_first` is off. A carried-over context window keeps a check from missing text split across chunks. A blocked chunk ends the stream with `content_blocked`. |
| Semantic topic judge | [spring-ai-recipes](https://github.com/habuma/spring-ai-recipes) `semantic-safeguard-advisor` | A separate cheap judge model returns a typed verdict (`allowed`, `reason`, `violatedRule`) and matches by meaning, not by words. |
| Word lists | [spring-ai-recipes](https://github.com/habuma/spring-ai-recipes) `safeguard-advisor` (Spring AI `SafeGuardAdvisor`), `output-safeguard-advisor` | Exact phrase blocking on input and on output. |
| Claim checking (phase 3) | [spring-ai-recipes](https://github.com/habuma/spring-ai-recipes) `fact-checking-evaluator`, `decomposed-claim-checking` | Claim decomposition and the strict "explicitly stated or necessarily implied" prompt. |
| Forced first search | Onyx `forced_tool_id` → `tool_choice: required` | Already in MemoryOS as `ModelBinding.requiredTools`, used by Deep Research. |
| Unknown citation markers | Onyx `citation_processor.py` | Drop `[n]` that points to no retrieved document. |
| Admin screens (Mobbin) | [WRITER Guardrails](https://mobbin.com/screens/b151471c-b5ec-4856-902e-a42950e44eb4), [Plain label](https://mobbin.com/screens/6784af21-18f4-4d82-b688-3deda0769406), [Vercel rule](https://mobbin.com/screens/7fe2d139-14f2-47f0-80bf-678499d87f75), [PlayAI](https://mobbin.com/screens/8f092d20-e28e-4f92-98ac-862430b0dde7) | See below. |
| Refusal UX (Mobbin) | [Amie](https://mobbin.com/screens/4bb72a95-0796-4fff-aae7-d73687d697e7) | Say what was not found, offer the nearest thing found, and suggest a follow-up. |
| Source selector (Mobbin) | [Gemini](https://mobbin.com/screens/39f47b67-e68c-46cc-a952-dabfad7bf43c) | Source chips under the composer. |

From **Amazon Q Business**, MemoryOS takes:
- response scope `ENTERPRISE_CONTENT_ONLY`;
- the optional end-user switch;
- blocked phrases (at most 20) with an admin message;
- topic controls: a description, up to five example messages, and the rule "block with a message" or "answer only from these sources", with Group include/exclude, the more restrictive rule winning, and topic rules taking precedence over the global setting.

From the **Mobbin admin screens**, MemoryOS takes:
- **WRITER:** the guardrail list (name, when it runs, Group scope, enabled switch);
- **Plain:** an AI description that teaches the model when an item matches;
- **Vercel:** the if/then rule block;
- **PlayAI:** the "only answer from the knowledge base" switch.

Onyx has none of this. Its persona has no grounded-only switch, an empty retrieval goes back to the model, and it has no topic moderation. This is a recorded departure from the Onyx baseline, as the conventions require.

The spring-ai-recipes safeguards are `ChatClient` `CallAdvisor`s. MemoryOS Chat runs Embabel's streaming prompt runner, so it takes their contracts and prompts, not the advisor classes. Embabel 1.5.2 does not run `AssistantMessageGuardRail` on streaming paths, and it runs `UserInputGuardRail` on every inference. The checks below are therefore MemoryOS steps, run once per turn, around the stream.

## Behaviour

Grounded mode is a policy on a turn. Where it applies, the turn passes three checks:

```
question
  ▶ Check 1  classify, once        conversational → answer normally
                                   blocked topic  → admin message
  ▶ Check 2  forced search         no selected evidence → refusal, no answer model call
  ▶ answer model writes            text held until the first valid citation
  ▶ Check 3  citations             no valid citation by the end → refusal replaces it
  ▶ answer with citations
```

### Check 1: classify

Before the answer model runs, one structured call on the Tenant's helper model classifies the question. It uses `createObject` on the Tenant-resolved runner, as `SearchTool.helper` does, so budget, deadline and usage recording apply.

- The call returns `conversational | question | blocked_topic` and, for a blocked topic, which topic matched.
- The instructions are the system message and the question is the user message.
- Short greetings and thanks match a fixed list in code first and skip the call.
- If the call fails or times out, the turn is refused: grounded mode fails closed.

### Check 2: forced search

- The first cycle forces `search_knowledge` through `ModelBinding.requiredTools`. This replaces, for grounded turns only, the Chat spec sentence saying Chat adds no provider-specific `tool_choice` behaviour.
- A model without tool calling cannot serve a grounded turn. Admission refuses it by name, as it refuses Deep Research.
- If the search selects no evidence, the turn ends with the refusal before any answer-model call. Both demo questions end here, and they end sooner than an answer would.

### Holding and Check 3

Streaming follows **option C**, the checked mode of the references at citation granularity:

- The server holds answer text only until the first `[n]` that points to evidence registered in this turn, then releases the held text and streams the rest as it arrives.
- Tool, activity and progress events stream throughout ("Đang tìm trong 12 tài liệu…").
- A marker pointing to no registered evidence is dropped as it passes (Onyx `citation_processor`).
- If the answer ends with no valid citation and is not itself a refusal, the person never sees its text. The refusal replaces it.
- Holding happens before `streams.append`, so the MEM-26 replay buffer only ever holds released text.
- The check itself is code, not a model call. The expected wait before the first text is about 1–3 seconds; it is measured, not assumed (see Verification).

### The answer instruction

Grounded turns add a strict block to the prompt:

- use only the retrieved documents, never general knowledge, even for common facts;
- cite each statement;
- name the part the documents do not cover.

### The refusal

The refusal is useful, not bare:

- It says the documents do not cover the question.
- It lists up to three nearest documents the search found (title and link) when there were any.
- It suggests a narrower follow-up.
- No explanatory copy is added under controls.

### Recorded reason

Every refused grounded turn stores a structured reason on the message: `no_evidence`, `uncited` or `blocked_topic`. The browser renders from it, and the benchmark reads it instead of matching wording.

## Web search

The default: when grounded mode applies, `web_search` and `open_url` are not offered, because their citations are not Tenant documents. The **end-user switch** follows the Amazon Q Business / Glean model:

- The administrator may allow people to turn Web search on for a turn in grounded mode.
- When they do, the answer is labelled as using Internet sources, and web citations count as valid for Check 3.
- When the administrator has not allowed it, the Web switch in the composer is disabled and shows a lock.

Image generation and the interpreter are unchanged, because they produce artefacts rather than facts.

## Topic guardrails

Delivered in the same pull request as grounded mode, in a reduced first cut. The full Amazon Q model follows later.

**In this pull request:**
- Three built-in topics: politics, leaders and religion. Each has a description and example questions written by MemoryOS, and the administrator can switch it on or off and edit its message.
- Blocked phrases: at most 20, with one admin message.
- Order of evaluation:
  1. Blocked phrases are matched in code first (instant, exact).
  2. Check 1 matches enabled topics by meaning, using the descriptions and examples.
  3. A match gets the admin message and no answer-model call.
- **Output side.** Released text is checked for blocked phrases in code before release. The citation gate keeps back as many trailing characters as the longest phrase, so a phrase split across two chunks is still caught; this is NeMo `RollingBuffer`'s `context_size` idea.
- **Coverage.** Topics apply to every turn in the Tenant, grounded or not, because the sensitive-topic request stands on its own.

**Later (the full Amazon Q model):**
- topics the Tenant writes, with up to five examples;
- the "answer only from chosen Sources" rule;
- Group include or exclude, with the more restrictive rule winning;
- a WRITER-style Guardrails list with Plain/Vercel-style forms.

**Limits.** No classifier is exact. The goal is to stop most attempts and record them; the `sensitive` benchmark category measures false blocks and misses.

## Where the settings live

- **Tenant.** `ChatSettingsService` gains `groundedAnswers` and `groundedAllowWeb`. They reuse the revision check, `MODELS_MANAGE` and the `CHAT_SETTINGS_CHANGE` audit record. When `groundedAnswers` is on, every turn in the Tenant is grounded.
- **Agent.** A `grounded` flag on the Persona, so one agent can be grounded while the Tenant is off. An agent cannot opt out of a Tenant that is on.
- **Topics and phrases.** Their own Tenant tables: the three built-in topics' enabled flag and message, and the phrase list. Group scope arrives with Tenant-written topics.

## Admin Chat page

`/admin/chat` is new. It is the MemoryOS counterpart of Onyx **Chat Preferences** (`/admin/chat-preferences`), which Onyx lists in its configuration block beside Language Models, Web Search, Image Generation, Voice and Code Interpreter. Only settings MemoryOS already has, or that this increment adds, appear on it; Onyx's other preferences are not copied.

The page has four sections, in this order:

| Section | Settings | Origin |
| --- | --- | --- |
| Features | Deep Research on or off | Moved from the Web search page |
| Conversation history | The visibility of conversations to administrators (full, anonymized, disabled) | Moved from the Web search page |
| Answers from documents | Grounded answers on or off; people may turn on Web search | New |
| Sensitive topics | The three built-in topics (switch and message each); blocked phrases (at most 20) and their message | New |

**Behaviour:**
- Each switch saves on change, as Deep Research does today, with the Chat settings `revision` check.
- The topic messages and the phrase list save with a section button.
- Everything requires `MODELS_MANAGE` and records `CHAT_SETTINGS_CHANGE` with the changed fields.
- No explanatory copy is added under the controls; titles carry the meaning.

**Admin navigation.** The page sits in the configuration group. The owner accepted this regrouping of the whole admin menu on 2026-09-26, after Onyx `admin-routes.ts`:

```
Cấu hình           Mô hình · Tìm kiếm Web · Giọng nói · Tạo ảnh · Code Interpreter · Chat
Trợ lý & công cụ   Quản lý trợ lý · Máy chủ MCP
Tri thức           Nguồn đang có · Thêm nguồn · Bộ tài liệu · Cấu hình tìm kiếm
Tổ chức            Người dùng · Nhóm · Nhà cung cấp đăng nhập
Giám sát           Chi phí AI · Lịch sử hội thoại · Nhật ký audit
```

This pull request adds only the Chat entry. The regrouping and the consistent names are a separate small pull request.

## Audit and metrics

- **Blocked topic or phrase.** Each one writes an audit record: who asked, when, which topic, which agent.
- **No evidence and uncited refusals.** Counted as metrics, not audited per person, because they are routine and not sensitive. The counts show where the Tenant lacks documents.

## As implemented

These departures from the sections above were found while building the change; the Chat spec records the behaviour.

- **No early stop when evidence is empty.**
  - Check: in Embabel 1.5.2 an `@LlmTool` exception becomes error text fed back to the model; only
    `ReplanRequestedException` escapes `MethodTool`. So a tool cannot end the streaming loop, and the answer model
    still writes after a search that found nothing.
  - Outcome: the release gate discards that answer and stores the refusal, so nothing is shown. The cost is the
    answer call.
- **Where the settings are stored.** Topics and phrases are JSONB columns on `chat_settings` (V132), not separate
  tables. They are one row per Tenant under the existing revision, and Group scope arrives with Tenant-written topics.
- **Classifier model.** Check 1 runs on the turn's own model through `ModelCalls` and records its usage as the `CHAT`
  flow. There is no new model flow, so no catalog or provisioning change.
- **Fail closed** uses the existing `CHAT_PROVIDER_UNAVAILABLE` failure code.
- **Deep research** is refused on grounded turns, because research runs its own agents and tools.
- **Grounded turns always search**, whatever the agent's search tool setting.
- **Topics apply to every turn** when enabled, grounded or not, because the sensitive-topic request stands on its own.
- **Browser.**
  - Web search the Tenant does not allow is hidden from the composer, as tools an agent forbids already are, rather
    than shown with a lock.
  - No "Đang đối chiếu nguồn…" line was added: the existing pending indicator and search activity show progress
    while text is held.
  - The declined-reply marker reads the stored reason, so it appears from history. The stream protocol is unchanged
    (MEM-26), and a live reply shows the refusal text itself.
  - The "Internet sources" label is not implemented; Web citations already show as Web sources.
- **Benchmark.** Abstention prefers `refusalReason` and falls back to the phrase list when it is null, because
  ungrounded replies carry null. Time to first text is not measured, because the client reads saved history rather
  than the stream. `baseline.grounded.json` is a placeholder until the first grounded staging run, and `baseline.json`
  must be re-recorded because 22 must-decline questions were added.

## Out of scope

- Intent routing and switching to an external model belong to MEM-129.
- Controlling what data leaves for external models belongs to MEM-134. Its first-slice design is kept in `docs/increments/active/mem-134-external-data-gate/`, not yet committed.
- The general evaluation programme belongs to MEM-176. This increment only adds the categories it needs to the MEM-141 benchmark.
- **Phase 3, waiting for an owner decision on cost and latency:**
  - per-sentence or per-claim grounding checks with a model (the Bedrock/NeMo chunked check with the recipes' claim-checking prompt);
  - numeric thresholds;
  - checking the output by meaning.

## Verification

- **Tests:**
  - the classifier contract and fail-closed behaviour;
  - the greeting fast path;
  - forced first-cycle search;
  - refusal before any answer-model call when evidence is empty;
  - holding until the first valid citation;
  - the uncited replacement and the dropping of unknown markers;
  - held text never reaching the replay buffer early;
  - Tenant and Agent precedence;
  - the Web switch permitted and not permitted;
  - the stored refusal reason;
  - blocked phrases on input and on output, including a phrase split across chunks; a matched topic; the audit record.
- **MEM-141 benchmark:**
  - new categories `general_knowledge` (the demo questions and similar) and `sensitive`;
  - a grounded-on run with its own baseline beside the grounded-off baseline;
  - a new leak metric, *asserted without citation* (not a refusal and no valid citation), which must be 0 with grounded mode on;
  - the false-refusal rate on answerable categories, reported as the on/off difference;
  - time to first text and total time, on and off.
- **Staging:**
  - the two demo questions refuse, with the nearest documents;
  - "xin chào" answers;
  - an in-corpus Tasco question answers with citations;
  - with Web allowed, a Web answer is labelled as using Internet sources.
