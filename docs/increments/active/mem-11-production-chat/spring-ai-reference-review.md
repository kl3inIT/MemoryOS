# MEM-11 — Spring AI references for production Chat

Source review: 2026-09-08. No application dependencies or runtime code changed; upstream samples were not built or executed. This review evaluates the newer requested direction: assistant-ui, MemoryOS-owned Chat history and background execution, Embabel's model/tool loop, Spring AI model integration, and the existing Search capability. It extends the [earlier Search recipe review](../../superseded/mem-46-authorized-rag/spring-ai-recipes-review.md), whose rejection of Embabel applied to the old fixed Search pipeline.

## Reference checkouts

All checkouts are ignored under `.tmp/`. Recipes already existed and `pull --ff-only` confirmed it was current. The other six checkouts were created for this review. All seven had clean worktrees after inspection. SHAs identify research evidence, not approved dependency versions.

| Upstream | Local directory | Reviewed SHA | Use |
| --- | --- | --- | --- |
| [habuma/spring-ai-recipes](https://github.com/habuma/spring-ai-recipes) | `.tmp/spring-ai-recipes` | `f89baf57e83e2007fb0ccb96852252c1fa1cff6a` | Small Spring AI and Embabel implementation patterns |
| [spring-ai-community/spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils) | `.tmp/spring-ai-agent-utils` | `fe1255dc5640c78d810169898ed500da2410cb3b` | Tool contracts, event handlers, optional clarification/skills |
| [spring-ai-community/spring-ai-session](https://github.com/spring-ai-community/spring-ai-session) | `.tmp/spring-ai-session` | `116746c22db4263ce170c34a1f946555b8b70778` | Turn-aware context compaction, event identity, JDBC examples |
| [spring-ai-community/mcp-security](https://github.com/spring-ai-community/mcp-security) | `.tmp/mcp-security` | `79bacd0a4ee87af46c1e1c4f5e42f51329486767` | Future MCP OAuth/resource security |
| [spring-ai-community/mcp-annotations](https://github.com/spring-ai-community/mcp-annotations) | `.tmp/mcp-annotations` | `422bd28dfe13b407c139720da8e98541ed9ac892` | Historical migration reference only; README says development moved to Spring AI 2.x |
| [spring-projects/spring-ai](https://github.com/spring-projects/spring-ai) | `.tmp/spring-ai` | `bf122ac7f92b805dd3391387004788456c5c03c6` | Official current source; sparse checkout of `mcp/mcp-annotations`, `spring-ai-model`, `spring-ai-client-chat` |
| [embabel/embabel-agent](https://github.com/embabel/embabel-agent) | `.tmp/embabel-agent` | `450e922ed7d072b8c563a6a5290155061a1e219a` | Actual streaming loop, provider adapter, tool context and integration tests |

Community selection prioritizes relevance and maintenance. The organization currently labels `agent-client`, `agent-bench`, `agent-judge` and several coding-agent repositories as moved to `markpollack`; `spring-ai-tool-search-tool` is archived. Those old locations were not cloned. The official Spring AI and Embabel repositories are additional upstream references, not Community-owned projects.

## Recipe reuse map

Paths below are relative to `.tmp/spring-ai-recipes` at the pinned SHA. Reuse means adapting the pattern to MemoryOS contracts, not copying a sample application's runtime configuration.

| Recipe and source | Reusable part | MemoryOS mapping and required adaptation |
| --- | --- | --- |
| `tool-rag/src/main/java/com/example/essentialrag/RagTools.java` | Model-visible retrieval tool with a typed input | `searchKnowledge` calls the authorized public Retrieval contract. Replace sample `VectorStore.similaritySearch`; preserve current evidence IDs and bounded result/context sizes. Do not log the user's query as the sample does. |
| `conversation-aware-rag/src/main/java/com/example/essentialrag/ChatClientConfig.java` | `CompressionQueryTransformer` and a separate query client | Resolve follow-ups from the selected permitted conversation branch. Keep the original query and a bounded fallback; integrate with the Onyx-style semantic/keyword/subquery plan inside the Search tool. Do not inherit an automatic retrieval advisor on every helper call. |
| `structured-output-validation/src/main/java/com/example/structuredvalidation/StructuredValidationApplication.java` | Typed output, provider structured output, schema validation and repair | Query plan and candidate-section selection. Additionally validate server candidate IDs, ranges, duplicates and budgets. The sample's five repeat attempts must not multiply independently across tool cycles and provider retries. |
| `embabel-invoke-programmatically/src/main/java/com/example/embabelagent/EmbabelAgentApplication.java` and `SupportAgent.java` | `AgentInvocation`, typed input/result and annotated actions | Invoke from application-owned background execution in the API process when that native API meets the turn contract. The recipe's console `ApplicationRunner` is not an HTTP, persistence or streaming implementation. Use current Embabel source for the actual tool loop. |
| `hybrid-rag/hybrid-rag/src/main/java/com/example/essentialrag/HybridDocumentRetriever.java` | Rank fusion and deduplication | Retain MemoryOS native OpenSearch hybrid retrieval per query. Fuse query result lists with the Onyx weighted-RRF baseline; the recipe instead fuses a separate Lucene and vector store with equal weights and `k=60`. Do not introduce those extra indexes. |
| `rerank-rag/rerank-rag/src/main/java/com/example/essentialrag/RerankingDocumentPostProcessor.java` | Structured relevance selection | Adapt to bounded section selection. The sample indexes directly into `documents` from model output; validate IDs/ranges/dedup first. Selection remains inside the Search tool, not a mandatory additional assessor after every agent cycle. |
| `persistent-chat-memory/jdbc-memory/src/main/java/com/example/chatloop/ChatClientConfig.java` | Distinction between a message window and its persistence adapter | Build model context from MemoryOS-owned history. A 500-message window and JDBC ChatMemory do not implement active-run coordination, SSE replay, citations, edit/regenerate branches or Tenant authorization. |
| `chatclientcustomizer` and the separate client in `conversation-aware-rag` | Client configuration by role | Share model transport and safe observations while keeping query/selection/answer tools and advisors explicit. |

`embabel-state`, `embabel-hitl`, `ask-user-question`, skills and subagent examples are secondary references if the product scope requires those behaviors. HyDE, semantic caching, autonomous coding tools and long-term auto-memory are not additions to the initial Onyx-aligned knowledge Chat. `chatclient-and-react` demonstrates ReAct reasoning, not a React browser adapter.

## Community findings that affect the architecture

### Spring AI Session: useful compaction, incomplete product persistence

Read these sources under `.tmp/spring-ai-session`:

- `spring-ai-session/src/main/java/org/springframework/ai/session/SessionEvent.java`: identity, timestamp, message, synthetic/archive flags and branch attribution.
- `spring-ai-session/src/main/java/org/springframework/ai/session/compaction/CompactionUtils.java`: `snapToTurnStart` moves a cut to a root user turn.
- `spring-ai-session/src/main/java/org/springframework/ai/session/compaction/TokenCountCompactionStrategy.java`: retains a contiguous suffix within the token budget, then aligns to a turn boundary.
- `spring-ai-session/src/test/java/org/springframework/ai/session/compaction/CompactionUtilsTests.java` and `TokenCountCompactionStrategyTests.java`: useful edge-case references; not executed here.
- `spring-ai-session-jdbc/src/main/resources/org/springframework/ai/session/jdbc/schema-postgresql.sql`: session/user/event storage, event version and ordered events.

Recommend reusing the turn-boundary/context-budget approach first. Its `branch` is an agent hierarchy label, not the parent-message tree required by edit/regenerate. The inspected schema has no dedicated Tenant, active-run coordination, cancellation, citation or UI branch-tree fields. Its session event log is not a replacement for MemoryOS transcript storage or transient SSE replay.

Keep transcript/outcome/citation persistence in concrete Chat repositories; transient sequence chunks belong in the replay buffer. Context compaction produces a derived model view and must not erase user-visible history. Check the oversized-single-turn case explicitly: alignment may retain no real turn, so the latest question and active tool pair need a defined bounded-context outcome. Summaries follow the accepted conversation-history semantics; fresh source reads still require current authorization. Direct adoption of the library remains a compatibility/integration decision; it is not approved merely by cloning it.

### Agent Utils: tool contracts and UI hooks

Read `spring-ai-agent-utils/src/main/java/org/springaicommunity/agent/tools/AskUserQuestionTool.java` and `TodoWriteTool.java`. The former exposes a synchronous `QuestionHandler`; the latter accepts a `TodoEventHandler` callback.

Reuse typed tool arguments, validation and the callback-to-event pattern. Real Search/tool progress should become MemoryOS run events consumed by the assistant-ui adapter. A model-authored todo list is not evidence that a run step executed. If clarification is included, persist a question ID and waiting state with an authenticated reply command; a synchronous callback alone does not provide durable browser pause/resume. Avoid introducing the example coding-agent shell/filesystem tools into knowledge retrieval.

### MCP Security and annotations: future protocol boundary

`mcp-security` is a useful reference when MemoryOS exposes or consumes remote MCP tools. It does not replace existing Actor/Tenant/Source authorization or provide the assistant-ui Chat transport. Its sample server `samples/sample-mcp-server/src/main/java/org/springaicommunity/mcp/security/sample/server/streamable/SecurityConfiguration.java` explicitly marks its Inspector-oriented broad CORS and disabled CSRF customization as unsuitable for production; do not transplant that configuration.

`mcp-annotations/README.md` directs current development to official `spring-ai/mcp/mcp-annotations`. Use that source and the selected Spring AI BOM if MCP becomes an implemented feature. The assistant-ui documentation MCP installed for development is independent of a product MCP runtime.

## Embabel and Spring AI integration boundaries

The target responsibilities remain:

```text
assistant-ui ExternalStoreRuntime
  -> MemoryOS REST commands + replayable SSE events
  -> Chat application / PostgreSQL messages, outcomes and citations
  -> application-owned background execution in the API process
  -> Embabel model/tool loop
       -> Spring AI model transport
       -> searchKnowledge / readDocumentSection
            -> existing authorized Retrieval capability
```

The inspected `embabel-agent-api/src/main/kotlin/com/embabel/agent/spi/loop/streaming/StreamingToolLoop.kt` executes inference, streams text, executes requested tools, appends results and continues. The provider-neutral loop owns execution; `spi/support/springai/streaming/SpringAiLlmMessageStreamer.kt` adapts Spring AI. These are implementation evidence for the intended split, not a promise that Embabel supplies MemoryOS run durability or its public browser protocol.

Two parity gaps need explicit integration work:

1. Embabel's inspected streaming loop exposes `Flux<String>` and throws when its iteration limit is reached. Tool progress, usage and transient stream replay require supported event hooks/adapters around that execution. Verify these before claiming a production streaming integration.
2. Onyx's baseline config uses six LLM cycles. The selected public Embabel streaming path uses a native limit of twenty; the inspected public caller does not expose a cycle-limit setter. The follow-up probe enforces MemoryOS's six-cycle baseline within that ceiling and disables tools for the final allowed inference. Native Budget.actions counts process actions, not these inference cycles. Token/cost accounting and policies remain native, with explicit streaming integration and checks at inference/tool boundaries; see the [end-to-end audit](framework-entrypoint-verification.md#end-to-end-audit--2026-09-09).

Current [Spring AI tool documentation](https://docs.spring.io/spring-ai/reference/api/tools.html), fetched through Context7, shows disabling auto-registration of `ToolCallingAdvisor` for externally controlled `ChatClient` execution. [Upgrade notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html) remove the old per-request `internalToolExecutionEnabled` flag. Verify the selected model/Embabel adapter rather than copying older 1.x examples. There must be one owner of the outer tool loop.

MemoryOS currently pins Boot `4.1.1` and Spring AI `2.0.1`. The inspected Session main POM matches those versions but is `0.9.0-SNAPSHOT`; Agent Utils main is `0.13.0-SNAPSHOT` with AI `2.0.1`; Embabel main is `1.5.2-SNAPSHOT`. This demonstrates source alignment, not released-artifact compatibility. Resolve stable releases and run actual dependency/streaming integration checks before introducing dependencies.

## Implementation order informed by this review

1. The design/plan reconciliation is now recorded in [the current design](design.md), with [Onyx parity](onyx-parity.md) and the retained [retrieval diagram](diagrams/README.md).
2. Implement the in-memory turn setup, DB transcript, selected branch and transient replay contracts; prove the selected Embabel/Spring AI streaming, cancellation and tool-event integration against those contracts.
3. Adapt recipe patterns for the authorized Search tools, follow-up query plan and validated section selection, preserving the Onyx retrieval baseline.
4. Add turn-aware context compaction and connect real history/events to assistant-ui; cover edit/regenerate, reconnect, stop, partial outcomes and citations.
5. Verify real-model behavior, permissions, failure recovery and agreed Onyx functional parity. Recipe context-load tests are not production acceptance.

No library from this catalog was added to MemoryOS dependencies. No application or upstream tests were run for this source-only review.

Follow-up on 2026-09-09: stable release resolution, isolated compile, real-model loop/cancellation checks and assistant-ui adapter tests are recorded separately in [Phase 1 verification](phase-1-verification.md). The source-review limitation above applies to this review, not to that subsequent probe.

The later [public-entrypoint verification](framework-entrypoint-verification.md) selects native PromptRunner/streamer/tool loop instead of the initial custom streamer. The [end-to-end integration design](design.md#end-to-end-framework-integration) reconciles ConversationFactory consumers, accounting and distinct resource limits across all six phases; it supersedes earlier speculative bridge/wiring recommendations in this source review.
