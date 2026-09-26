# Plan

Owner decisions of 2026-09-26:
- one pull request;
- streaming option C;
- Web search: an end-user switch the administrator may allow;
- audit per blocked topic, metrics for routine refusals;
- topic guardrails in a reduced first cut;
- MEM-134 deferred and its worktree removed.

## One pull request

Status 2026-09-26: items 1-14 implemented on the branch; see design "As implemented" for departures. Open: CI, review, staging acceptance and the grounded baseline.

### Backend

1. **Migration**
   - `chat_settings.grounded_answers` and `chat_settings.grounded_allow_web`.
   - `persona.grounded`.
   - `chat_guardrail_topic` for the three built-in topics: key, enabled, message. The descriptions and examples stay in code.
   - `chat_guardrail_phrase`: at most 20, plus one message.
2. **Settings**
   - `ChatSettingsService` gains grounded answers, allow Web, topics and phrases, with the revision check, `MODELS_MANAGE` and audit fields.
   - Persona input and view gain `grounded`.
   - `openapi.yml` and `web/src/lib/hey-api`.
3. **Admission** (`ChatTurnService.admit`)
   - Resolve `ChatTurnOptions.grounded`: Tenant on, else the Agent flag.
   - Refuse a model without tool calling.
   - Withhold `web_search`/`open_url` unless the Tenant allows Web and the person turned it on for the turn.
4. **Check 1** (`chat.grounding`, new; runs for every turn when a topic or phrase is enabled, and for grounded turns)
   - Blocked phrases are matched in code first.
   - Greeting fast path.
   - `createObject` classifier on the Tenant helper runner, returning `conversational | question | blocked_topic` plus the topic.
   - Fail closed.
   - A blocked turn gets the admin message, no answer call, and an audit record.
5. **Check 2**
   - `requiredTools` on the first cycle of a grounded turn.
   - When `SearchTool` selects no evidence, end the turn with the `no_evidence` refusal before the answer call.
   - Carry the nearest candidates the search saw.
6. **Prompt:** a grounded block in `ChatPrompts`, grounded turns only.
7. **Release gate** (before `streams.append`)
   - Grounded turns hold text until the first `[n]` present in `ChatEvidence`, and drop unknown markers.
   - A trailing window as long as the longest blocked phrase is kept back until the phrase check passes.
   - On finish: no valid citation gives the `uncited` refusal; a blocked phrase gives the admin message.
8. **Message:** the refusal reason (`no_evidence | uncited | blocked_topic`) and the nearest documents are stored and returned in history.
9. **Audit and metrics**
   - A `CHAT_GUARDRAIL_BLOCK` record (actor, topic or phrase, agent, session).
   - Counters for `no_evidence` and `uncited`, tagged with the agent.

### Web

10. **Admin page "Chat"** (`/admin/chat`, new, in the configuration group)
    - Features: Deep Research, moved from the Web search page.
    - Conversation history: the administrator visibility, moved from the Web search page.
    - Answers from documents: grounded answers and allow Web.
    - Sensitive topics: the three topics (switch and message) and blocked phrases with their message.
    - Remove both moved sections from the Web search page.
11. **Agent editor:** the grounded switch.
12. **Chat**
    - The composer label "Chỉ từ tài liệu công ty".
    - The Web switch, locked or allowed.
    - The "Internet sources" label.
    - "Đang đối chiếu nguồn…" while text is held.
    - The refusal card with the nearest documents and a suggested follow-up.
    - The blocked message card.

### Benchmark and docs

13. **Benchmark** (`tools/rag-benchmark`)
    - The `general_knowledge` and `sensitive` categories.
    - Read the refusal reason.
    - The *asserted without citation* leak metric.
    - Time to first text.
    - A `--grounded` run with `baseline.grounded.json`.
    - The `sensitive` category is gated at zero answered.
14. **Docs:** Chat spec (grounded mode, guardrails, the `tool_choice` sentence), `docs/tests/chat.md`, the audit action in `docs/specs/audit.md`, roadmap, and the NeMo reference.

## Later

- Tenant-written topics with examples.
- The Sources rule and Group scope.
- The Guardrails list page.
- Phase 3 grounding checks.

## Verification

- `./gradlew :core:test :api:test`, then CI `clean check`.
- Web: `pnpm typecheck`, `pnpm build`, and the changed Vitest tests.
- `rag-benchmark run` with grounded mode on and off on staging:
  - *asserted without citation* = 0 with it on;
  - `sensitive` answered = 0;
  - false refusal and time to first text recorded in `verification.md`.
- Staging:
  - the two demo questions refuse, with the nearest documents;
  - a leaders question is blocked with the admin message, and an audit row appears;
  - "xin chào" answers;
  - an in-corpus Tasco question answers with citations;
  - with Web allowed, the answer is labelled as using Internet sources.
