# Chat session contract

## Implemented scope

MEM-11 implements private sessions, native background execution, local Stop and bounded RAM replay/SSE in the working tree through Phase 2.3. The application home remains Search; Chat UI is Phase 2.4. [Design and future scope](../increments/active/mem-11-production-chat/design.md), [verification matrix](../tests/chat.md) and [ADR 0009](../decisions/0009-chat-session-persistence-and-iam-boundary.md) distinguish these boundaries from complete Chat acceptance.

## Ownership and names

`ChatSession` belongs to the configured Tenant and an Actor. An active membership is necessary; even a different member/admin cannot read another Actor's private session through these endpoints. Identity comes from the existing principal; Tenant/owner fields are not accepted in the creation body. Missing, wrong-owner and inaccessible session lookups return `CHAT_UNAVAILABLE` without disclosing contents. The existing API membership filter rejects revoked membership with 403 before Chat handling.

`Persona` is the AI role configuration, not a user profile. The built-in default Persona is provisioned idempotently when an authorized session is first created; subsequent creates reuse its identity and apply the configured defaults. `memoryos.chat.persona.name`, `.instructions` and `.model` have initial defaults MemoryOS, a general assistant instruction, and gpt-5-mini. The runtime registers this configured model as a native Embabel LlmService; current option conversion targets the probed GPT-5 Chat Completions baseline. Persona editor/model permission overrides are not implemented.

`ChatMessage` stores a root, USER or ASSISTANT node and its parent/latest child. Root nodes are not returned as visible history. Latest child must be a direct child in the same session, enforced by a deferred composite FK. One root and at most one RUNNING assistant per session are enforced by partial unique indexes. `runId` for the single-model path is the reserved assistant message ID; no separate ChatRun table exists.

## HTTP

| Method and path | Contract |
| --- | --- |
| POST `/api/chat/sessions` | `{title}` of 1–200 characters, private session/root created atomically; 201 response |
| GET `/api/chat/sessions` | Owner-filtered list, updated descending then ID; offset 0–10000, limit 1–100 (default 30) |
| GET `/api/chat/sessions/{sessionId}` | Session identity, title, Persona/root IDs and timestamps |
| GET `/api/chat/sessions/{sessionId}/messages` | Selected-branch messages after an optional message-ID cursor, limit 1–100 (default 50); cross-session/off-branch cursor rejected |
| POST `/api/chat/sessions/{sessionId}/messages` | `{parentMessageId, clientRequestId, text}`; 202 returns stable user/assistant IDs; execution runs after reservation commit |
| POST `/api/chat/sessions/{sessionId}/messages/{assistantMessageId}/cancel` | Owner-authorized, idempotent Stop request; 202 returns observed status. Read history for committed outcome |
| GET `/api/chat/sessions/{sessionId}/messages/{assistantMessageId}/events` | Owner-authorized SSE; optional `Last-Event-ID` or `after` is `assistantMessageId:sequence`; conflicting, malformed, foreign-run and future cursors are rejected |

An empty history means no visible messages; the root ID is in session metadata. Clients use the last returned message ID as the next `after` cursor; an empty page ends traversal. HTTP mutations require the current browser CSRF token and `X-MemoryOS-CSRF: 1` header under the existing session security policy. OpenAPI and browser clients are generated from controllers, with explicit required/nullable response fields.

## Internal persistence contracts

Within a transaction, IAM shared-lock revalidation precedes the session write lock. A send reservation atomically creates USER and ASSISTANT nodes and updates latest-child pointers. Retrying the same client request ID with the same parent/text returns the same message IDs; changed parent/text conflicts. A different send while a reply is RUNNING conflicts. The internal API does not yet accept model overrides, so command equality currently covers parent and text only.

The USER row retains `original_assistant_message_id`, written with the initial pair. Retry resolves that original assistant even if latest-child selection has changed or another turn is RUNNING; it neither changes branch selection nor creates messages. The original reference is required on USER rows and must identify a direct child in the same session. It is internal persistence data, not a history response field. Branch changes are exercised with database fixtures; edit/regenerate endpoints remain unimplemented.

Request idempotency, composite tree constraints and conditional PostgreSQL terminal writes are MemoryOS implementation choices, not claims of identical Onyx mechanisms. Their rationale, source evidence and costs are in the [Phase 2.1 comparison](../increments/active/mem-11-production-chat/design.md#phân-biệt-onyx-và-lựa-chọn-phase-21). Private-only access is this phase's implemented scope; planned sharing and any future administrative transcript access require their own authorized operations.

Continuation accepts the selected leaf, not arbitrary branch edits. Session history is bounded to 9999 nodes including the root; input text is limited to 32000 Java characters. Internal reservations receive a positive timeout up to 30 minutes. A terminal write accepts up to 1000000 characters, changes RUNNING once, and cannot overwrite a terminal result. Local Stop accepted before the immutable terminal decision wins cancellation. PostgreSQL converts writes after the durable deadline to FAILED. Finalization reads back the actual DB winner before publishing an outcome; a successful conditional write does not mean generation succeeded.

Flyway V18 owns the session schema; V19 adds execution metadata, and V20 removes the former DB Stop marker without rewriting V19. SQL and locks stay in `chat.persistence`; actor-facing application services own access and transactions. No temporary execution profile, fixtures, provider credentials or QA files are installed in the application.

## Execution lifecycle

The API owns a Spring SimpleAsyncTaskExecutor using virtual threads, without replacing Boot's applicationTaskExecutor. ChatTurnService admits at most the configured number of active turns, including outcomes waiting for a DB retry; there is no Chat queue or worker. Native Reactor cancellation terminates the stream on Stop. Shutdown signals cancellation and waits up to five seconds for task finalization before Spring closes the executor. No Thread handles or manual thread shutdown are retained. A process kill or exhausted drain can still lose unsaved partial output.

ChatTurnPersistence loads authorized product context. The builtin Persona is configuration-backed and refreshed on a new send, including existing sessions; the Phase 5 editor must explicitly transfer settings ownership. The current question plus instructions is validated before inserting messages. ChatTurnSetup resolves native Embabel messages and bounds context with Spring AI's O200K tokenizer plus framing allowance. The DB read is capped at 201 ancestor nodes and 1 MiB of content; model input omits root/placeholder nodes and an orphan oldest assistant. Selected context is in RAM; no full configuration snapshot is persisted.

ChatModelExecutor calls native PromptRunner, streamer and tool loop. StreamingLlmService binds verified streaming capability without a discovery inference. ChatModelGuard validates finish metadata, applies the final-cycle request policy, and records native LlmInvocation usage once per inference. Native Embabel computes totals/cost and evaluates configured budgets. Missing usage/pricing stays unknown; there is no custom pricing engine. Real Search tools and their budget/authorization boundaries arrive with Phase 3.

Stop authorizes the assistant/session and signals local execution directly. A bounded array of session command locks prevents Stop from overtaking reservation/registration. No active-row Stop polling or replica signaling remains. PostgreSQL sets the authoritative deadline; JVM clocks must remain synchronized for local cancellation timing. Reconciliation closes up to 100 expired RUNNING rows per pass after a five-second finalization grace. No model/tool call is retried. Terminal persistence can retry while keeping the active slot; process death can lose output not yet saved.

The provider composition owns reusable synchronous/asynchronous Spring AI SDK clients, explicit timeout, disabled automatic retries, close hooks and native observations/meters. `ChatModelBinding` retains the native SpringAiLlmService and provider final-request policy; each run resolves it into its RAM setup before dispatch. Decorating a model preserves converter/pricing/capabilities/adapters. The generic executor uses `Ai.withLlmService` and has no OpenAI options cast. Full catalog/user/admin/organization BYOK design and the extension test entry points are in the [provider architecture](../increments/active/mem-11-production-chat/provider-model-architecture.md); their implementation is [MEM-77](https://linear.app/memory-os/issue/MEM-77), blocked by MEM-11.

Provider endpoints are server-owned configuration. HTTPS remains the default; explicitly configured HTTP endpoints are supported for internal deployments. HTTP(S), host and URL structure are validated before SDK construction; URL credentials, query strings and fragments are rejected without echoing their values. Network transport protection is a deployment responsibility; Chat does not infer trust from a hostname or categorically reject internal HTTP.

## Streaming and reconnect

`StreamBufferWriter` owns ordered text/outcome events in RAM. `text-delta` and `outcome` carry the assistant ID and sequence; only an outcome published after the terminal transaction commits confirms completion/cancellation/failure. Missing, evicted or expired prefixes produce an unnumbered `reset` with `BUFFER_MISSING`, `BUFFER_GAP` or `BUFFER_EXPIRED`. Consumers then read history and poll RUNNING with a bounded policy; they must not start a new inference automatically.

The MVC controller returns `ResponseEntity<Flux<ServerSentEvent<Object>>>`, following OrgMemory's HTTP composition style. Spring owns SSE writes and subscription lifecycle. `ChatEventStream` reads bounded batches on the existing virtual-thread executor, with prefetch 1 and resource cleanup on cancellation/error/completion. HTTP disconnection or connection timeout closes only its reader. The model writer is independent. MVC socket writes remain blocking on Boot's application executor; this is not a WebFlux migration or a claim of higher throughput.

Authorization and cursor preflight run synchronously before the response. Reader allocation is deferred until subscription, and each subscription owns its own cleanup; an unconsumed publisher holds no reader slot. Terminal persistence uses a nonblocking finalization claim independent of the state monitor, so a slow terminal write does not hold the monitor used by Stop and text callbacks. Only one finalizer can release admission or publish the database outcome.

Defaults under `memoryos.chat.stream`: 4 MiB per run, 64 MiB total accounted replay budget, 10-minute TTL, 16 KiB text chunks or 25 ms flush, 128 KiB reader lag/batches, 4 readers/run and 64/process, 15-second heartbeat and 10-minute connection timeout. Accounting includes UTF-8 payload plus fixed per-event overhead; it is not a JVM heap measurement or serialized-wire byte limit. Buffer slots conservatively reserve the full per-run budget, so default retention admits 16 streams; completed streams are evicted first. Slow readers reset without blocking writers. Cursor-based replay and live reads share one sequence under the buffer lock; network and DB operations do not run under that lock.

Maintenance and flush use two Spring-managed virtual scheduler threads so a DB reconciliation wait does not block flushing. Chat executors are excluded as default candidates, preserving Boot's application executor. The existing Nginx honors `X-Accel-Buffering: no`; responses use `Cache-Control: no-store, no-transform`. The generated SSE SDK is retained, while the parser excludes this operation from TanStack query generation because its cursor represents a stream, not page fetching. Restart loses RAM replay; PostgreSQL history and deadline reconciliation remain authoritative.

Configuration: MEMORYOS_CHAT_API_KEY (fallback SPRING_AI_OPENAI_API_KEY) and optional MEMORYOS_CHAT_BASE_URL supply server-side provider access. Missing credentials reject a new send before reservation while history/cancel and other capabilities remain available. The current converter requires the GPT-5 model family; gpt-5-mini is the verified baseline, not certification of every compatible model. Execution defaults are concurrency 8, deadline 2 minutes, six cycles, 4096 output tokens, 32000 context tokens and 1000000 answer characters. Explicit token/cost caps use native Budget; a monetary cap requires configured input/output pricing. Basic pricing does not certify cached-token billing parity. Provider transport has automatic retries disabled.

A nonblank provider finish reason, including length, ends an answer normally when text exists; empty answers fail with CHAT_EMPTY_RESPONSE. EOF without terminal metadata fails and keeps partial text. Incomplete tool arguments at a length boundary must not execute. Safe failure codes and exception class names provide diagnostics; prompt/provider exception payloads are never logged. Send returns stable message IDs, and history carries the authoritative status used for reload.
