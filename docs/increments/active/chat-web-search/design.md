# Chat Web Search and URL reading

## Accepted scope

Implement external search and page reading in the existing Chat turn. Follow Onyx reference 40eb240df: separate search/content providers, model-selected tools, explicit disabled/automatic/forced intent, bounded crawling and safe URL fetching. Reuse assistant-ui tool renderers, existing composer controls, citation panel, stream, Stop and history. No new worker, agent, MCP server, quota dashboard, AI security pipeline or OCR changes.

External providers: Brave, Tavily, Exa, Serper, Google PSE and SearXNG. Content providers: built-in, Firecrawl, Exa and Tavily. Exa/Tavily use one connection for search and content. Native OpenAI/Gemini/Anthropic search is a separate approved extension, not a claim about Onyx external search. It references existing LLM credentials and is only available after actual protocol/model support; no second search LLM, automatic paid fallback or cosmetic enabled state.

## Consumer contract

Administrators configure tenant-owned connections and defaults using existing model-management authority. Members see availability, not secrets. Search connections are admin-controlled endpoints; arbitrary pages from a user/model use a separate SSRF-safe public reader. Provider credentials never accompany page requests. Limits remain server defaults, not a new UI settings matrix.
Every provider connection accepts an optional custom endpoint that replaces the provider's default base URL (SearXNG requires one); the same HTTP(S)/no-credentials/no-query validation applies. This supports routing a provider's protocol through an internal gateway.

Send/Edit/Regenerate carry webSearch off/auto/required. Missing means off. The choice is persisted in command identity so retries with changed intent conflict. Internal searchEnabled remains unchanged. Resolve configuration once per turn and never mutate pooled model clients. Required needs real enforcement, not a prompt-only promise. Unsupported modes fail before execution.

## Runtime and reuse

ChatModelExecutor registers web_search and open_url alongside existing tools through Embabel. Provider-neutral results enter ChatEvidence and the existing sources JSONB; web source identity is a URL, never a fabricated Document UUID. Existing turn deadlines, context budgets and cancellation cover network tools. Read content is bounded untrusted tool data. No arbitrary HTML execution or authenticated browser scraping.

Use JPA for connection/default CRUD and existing ProviderCredentials encryption. Keep existing JDBC command transaction mechanics. UI uses installed assistant-ui primitives/elements and current query/runtime state. The upstream Web Search element is a display, not an executor: replace demo counts/timers with actual server events and translate labels. Composer has one Web action, not a separate Fetch button.

## Delivery boundaries

Build the external vertical slice first, extend provider adapters next, then implement native protocol adapters with event/citation/continuation preservation. Incomplete adapters remain unavailable. Local mocks prove contracts, not live key acceptance. No commit, PR, deployment or external configuration change is implied by implementation.

## Implemented slice and explicit baseline gaps

Completion follow-up: the selected external provider's `site:` capability now enters actual request guidance (Exa: no `site:` syntax), without silently rewriting queries. Built-in PDF reading uses Apache PDFBox's text extraction over the already size-bounded public HTTP response, with bounded pages/output and cancellation checks; no OCR or ingestion job is introduced. Browser Web preference is actor/session scoped within the deployment origin, separate from the server-authoritative command intent and runtime-owned message state. Native hosted search must preserve the active model's protocol data and cannot be replaced by a second search-model call or enabled based on its name.

### Spring AI reader versus direct parser reuse

Inspected Spring AI 2.0.1 `PagePdfDocumentReader` and the local `JsoupDocumentReader` at `bf122ac`. These wrappers themselves use PDFBox/Jsoup. They provide `Document`/metadata, HTML selectors and PDF page grouping, margins and **page-range selection**; do not claim that the PDF wrapper must extract all pages. Their `get()` path materializes a list, without a direct caller-supplied bounded output writer or per-glyph cancellation hook. The Web consumer needs a bounded `{url,title,text}` result, not an indexing batch. Direct parsing therefore reuses the underlying libraries with a small text writer and PDFBox extension points; it does not replace Spring AI's ETL or the Source ingestion flow. Cost: MemoryOS owns resource closing, partial-excerpt labeling and cancellation tests. Revisit the wrapper if Web results become actual ETL inputs or its public hooks meet these needs. In either case, MemoryOS fetches URLs first and passes bytes, never untrusted URL resources into the wrapper. PDFBox 3.0.8 matches the version already selected by the existing Tika 4.0.0 parent; this is not an OCR/parser-stack upgrade. Reference: [Spring AI ETL](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html), [PDF reader source](https://github.com/spring-projects/spring-ai/blob/v2.0.1/document-readers/spring-ai-pdf-document-reader/src/main/java/org/springframework/ai/reader/pdf/PagePdfDocumentReader.java).

Follow-up accepted 2026-09-13: use `queries` and `urls` arrays like Onyx, with bounded admission (8 queries matching the existing query-progress contract, 5 URLs per call). Reuse SearchTasks parallel execution, order preservation, cancellation and the current deadline; no second executor framework. Preserve successful entries if another item fails. Compose tool guidance from actual model-request tool definitions. After a completed Web search, encourage reading promising/reputable pages unless snippets suffice, only when `open_url` remains available and this is not the final answer cycle. This adapts Onyx `tool_prompts.py`, `chat_prompts.py` and `llm_loop.py` at 40eb240df; it does not promise unsupported image/PDF/browser tools.

The external protocols, V49 connection/command persistence (authored as V43, renumbered after main added V43–V48), one-row composer control, settings, actual progress and URL source panel are implemented locally. Required external search uses the OpenAI Chat Completions named-tool option via the current adapter; it is not OpenAI hosted Web search. The adapter contract advertises that capability explicitly, separately from model naming and native search.

The built-in reader supports HTML/XHTML/plain text and text-based PDF, with the existing 20 MiB fetch ceiling. PDF text is limited to the first 50 pages and 16,000 characters plus a truncation marker; malformed, extraction-restricted or textless PDFs fail without OCR or invented evidence. Browser reload restores the actor/session Web preference; new-chat selection is saved when its server session is created. Storage denial does not prevent chatting. This is browser-local preference, not cross-device synchronization. Native OpenAI/Gemini/Anthropic adapters, protocol-native usage/continuation, and live keys remain unverified/unimplemented, not silently removed from the approved plan. See the plan for remaining work.

## Native OpenAI Web search (owner-approved 2026-09-13)

Owner approved OpenAI first; Gemini/Anthropic stay open. This goes beyond the Onyx 40eb240df baseline, which has no provider-hosted search, and is explicitly accepted as an extension.

Protocol facts, verified 2026-09-13: OpenAI hosted search for general models exists only in the Responses API as the `web_search` tool. Output includes `web_search_call` items (`action.query`) and `url_citation` annotations on `output_text`. Chat Completions `web_search_options` works only for specialized search models (`gpt-5-search-api`, `*-search-preview`) without Responses search features. `extraBody` on the existing Chat Completions model is therefore not a solution. Spring AI 2.0.1 and Embabel 1.5.1 have no Responses chat model. openai-java-core 4.49.0 (already resolved) provides `ResponseCreateParams`, `WebSearchTool`, `FunctionTool`, `ToolChoiceTypes`, `ResponseReasoningItem`, `ResponseIncludable` and the streaming events. Public list price is per search call plus search-content tokens; MemoryOS does not hard-code it.

### Seam

`OpenAiChatProviderAdapter.create` returns a Responses-backed `ChatModel` when, and only when, the model configuration declares `options.webSearch = "native"`. Every other OpenAI configuration keeps `OpenAiChatModel`. `ChatModelGuard`, Embabel's streaming tool loop, `MessageAggregator`, tool advertising, context validation, deadlines and Stop stay unchanged, so no Embabel internals are replaced. The model converts:

- Spring AI instructions → Responses input items: system/developer, user text/images, assistant text, `function_call`, `function_call_output`.
- `ToolCallingChatOptions.getToolCallbacks()` → `FunctionTool`s, plus one hosted `WebSearchTool`.
- Stream events → `ChatResponse` chunks. Text deltas become text; completed `function_call` items become `AssistantMessage.toolCalls`; `response.completed` supplies usage and finish reason.
- Failed or incomplete responses → the existing `CHAT_INCOMPLETE_RESPONSE`/provider errors, without exposing raw provider text.

### Stateless continuation

Requests use `store=false` and send the full history each cycle, matching MemoryOS replay/persistence. Reasoning models require `include=["reasoning.encrypted_content"]`. Reasoning and `web_search_call` output items are echoed back in the next tool cycle. They ride in `AssistantMessage` properties under one private key. Verified path: `MessageAggregator` merges output metadata; Embabel `toEmbabelMessage` preserves metadata for `AssistantMessageWithToolCalls`; `toSpringAiMessage` restores it. That is the same route Embabel uses for Gemini thought signatures. Plain final assistant text drops metadata, which is acceptable because it does not continue. Nothing of this is persisted across turns: prior turns replay as text.

### Evidence, events and citations

The Responses model receives the turn's `ChatEvidence` and `Consumer<ChatSearchEvent>` through a per-turn binding hook. Each `web_search_call` emits STARTED/SEARCHING (with `action.query` when present)/COMPLETED. Each distinct `url_citation` registers a `web:<url>` source with title and a bounded excerpt of the cited span, reusing `ChatSource.WebLocation` and `evidence.register`, so the Sources panel and history behave as for external search. v1 does not splice inline `[n]` markers at annotation offsets into an already-streamed answer; hosted-search citations appear in Sources only. External `web_search`/`open_url` tools are not registered for a native turn: one Web path per turn, no second paid search.

### Selection, required mode and cost

- `options.webSearch` accepts only `"native"` for the OpenAI adapter and requires tool calling. It is an explicit per-model administrator declaration, never inferred from the model name. Web availability exposes native-capable model IDs; the composer Web action works without an external connection when the selected model is native-capable.
- Command identity stays `off|auto|required` plus `modelConfigurationId`; no new enum value or column. A later administrator edit of the model option does not make replay conflict. This is accepted.
- `required` uses `tool_choice: {type: "web_search"}` on the first cycle. The guard's required-seen signal accepts a native `web_search_call`. `ChatTurnService` checks the adapter's declared support instead of `instanceof OpenAiChatModel`.
- Native search calls are counted in bounded metrics and events. Cost remains unknown unless configured; missing values are not recorded as zero.
- The Web settings page exposes native search as a per-model switch on providers whose adapter descriptor declares `nativeWebSearch`; the toggle writes `options.webSearch` through the existing model update API. Connection save/test failures render typed danger messages via the shared problem presentation.

### Verification

A local SSE fixture replaces OpenAI via the adapter base URL. Scenario 1: hosted search → text deltas → annotation → completed usage. Scenario 2: `function_call` → internal tool → second cycle echoes reasoning/search items. Also required mode, failed/incomplete responses, Stop and no external provider request. Real-key acceptance is an owner-run, paid gate.
