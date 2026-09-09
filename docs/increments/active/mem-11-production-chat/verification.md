# MEM-11 implementation verification

Current scope, 2026-09-09: one API process and one provider. Phase 2.3 implements RAM replay, local Stop and provider-specific composition in the working tree. Multi-provider catalog/user/admin/organization BYOK are [MEM-77](https://linear.app/memory-os/issue/MEM-77), assigned to `phamnhatanh811` and blocked by MEM-11; personal BYOK is excluded. Browser Chat remains Phase 2.4. The former Redis/replica plan is superseded by [design](design.md#baseline-một-provider-và-phần-mở-rộng) and [plan](plan.md#23--stream-buffer-và-http-contract).

## Phase 2.3 — Provider binding, local Stop and HTTP stream, 2026-09-09

Implemented: native SpringAiLlmService binding resolved in RAM, reusable provider clients with timeout/retry/close/observation policy, V20 removing DB Stop signaling, local cancellation, authoritative terminal publication after DB commit, bounded RAM replay, and the authenticated events route. The controller uses `ResponseEntity<Flux<ServerSentEvent<Object>>>` after comparing OrgMemory's `AssistantController.chat`/`UiMessageStream` with manual SseEmitter delivery. Spring owns HTTP writes/subscription lifecycle; MemoryOS retains bounded replay and a model execution independent of reader cancellation. MVC writes still block on a managed executor. No throughput advantage over SseEmitter was measured or claimed.

Verification:

- `gradlew.bat clean check --no-daemon --console=plain`: passed in 2m18s after the executor registration fix, 255 passed (core 159, API 57, worker 22, connector 17), 4 optional skips (one Chat, three Docling). The first full run caught a Chat scheduler disabling Boot's applicationTaskExecutor; excluding the scheduler as a default candidate fixes it, and the existing bearer/virtual-thread suite passes.
- After the final required/nullable SSE DTO change, `:api:test --tests 'io.memoryos.api.chat.*' --tests '*OpenApiContractTest'` with the explicit live Chat/OpenAPI flags and Infisical dev passed in 1m39s: 16 tests, no skips/failures. The live test calls OpenAI through product client composition, native runner and persisted history/usage. This is not live Keycloak/browser acceptance.
- `nginxHttpDisconnectReconnectAndStopUseIndependentExecution` starts the exact pinned Nginx image/config from `web/`, changing only the upstream address and unused object-storage CSP placeholder. Real HTTP uses signed fixture JWTs/JWKS and the real IAM binding/membership filter. Text arrives while DB status is RUNNING; disconnect releases the reader without canceling inference; cursor reconnect does not repeat text; Stop cancels the native stream and yields CANCELED with persisted partial; unauthenticated/non-owner requests are denied. Model transport is deterministic in this proxy test, and called once. Testcontainers reaps Nginx/PostgreSQL.
- `ChatEventStreamTest`: replay ordering/terminal, virtual-thread reads, disconnect cleanup, no-demand slow-reader reset, connection timeout without false outcome, and unnumbered missing-buffer reset pass. `StreamBufferWriterTest` covers byte admission, Unicode chunks, eviction/TTL/cursors and reader limits. These are bounded-contract checks, not a load benchmark or token recovery claim.
- `ChatTurnServiceTest` confirms pending DB failure emits no terminal, retry uses the stored winner, Stop before dispatch, admission/rejection cleanup and bounded shutdown. PostgreSQL/API tests retain tree/ownership/idempotency/deadline invariants, and prove late local completion cannot overwrite or announce success over a persisted failure.
- `ChatModelBindingTest` and `alternateNativeBindingUsesSameExecutorWithIsolatedPerTurnAccounting` exercise non-OpenAI options/metadata through the same native executor and two isolated accounting scopes. This is the MEM-77 extension example, not support for a second provider.
- OpenAPI generates typed text/outcome/reset payloads and the SSE SDK. Hey API 0.99 initially treated the stream's `after` cursor as TanStack pagination; a parser `isQuery` hook excludes only this operation while preserving the SDK and all other query helpers. Frontend pinned pnpm 11.22.0 checks pass: 52 unit tests, types/lint/format, generated-client/route drift and production build.
- JetBrains inspected changed Java/configuration files with warnings enabled and built successfully. Remaining diagnostics are the owned private Active monitor, the existing positive predicate, standard SSE/Nginx/custom CSRF header recognition and local test HTTP. The IDE still fails to resolve two cross-module YAML imports; their resources exist in core and full API startup tests resolve them. No fully IDE-clean claim is made.

Limits: RAM replay is single-process and temporary; conservative slot reservation retains at most 16 buffers with defaults, and byte accounting is not a measured JVM heap limit. Browser rendering/reconnect UX and live Keycloak Chat acceptance remain Phase 2.4. The earlier Claude review did not review this new Phase 2.3 code. Work is uncommitted on `feat/mem-11-production-chat`; no staging/production database or deployment was changed. Canonical contracts and test names are in the [Chat spec](../../../specs/chat.md) and [matrix](../../../tests/chat.md); scratch remains ignored.

## Phase 2.2 — Native execution and review, 2026-09-09

The receipts in this section are historical Phase 2.2 evidence. The subsequent provider/catalog architecture and Phase 2.3 code above are not covered by the earlier Claude review.

Implemented in the working tree: V19 Stop/outcome metadata, in-memory native-message setup, public Embabel PromptRunner streaming, Spring AI provider composition, background send/Stop APIs and generated client. PostgreSQL remains the conversation authority. SSE/replay and browser Chat remain 2.3–2.4; no deployment or commit is implied.

The optimization removes per-turn Thread handles and manual interrupts. Spring owns a virtual-thread executor; Reactor propagates cancellation. Chat owns bounded admission, an immutable terminal outcome and DB finalization retries. Shutdown waits for task finalization before executor teardown; it does not start synchronous DB retries after that bounded wait. Native process references and provider clients are cleaned up. Known usage enters native LlmInvocation/PricingModel; unknown usage/pricing stays null.

Claude Fable 5.1 (`claude-fable-5-1`) completed two read-only CLI review turns: implementation, then the entire six-phase plan, with local Onyx and Embabel source inspection. Both returned success. The first two launcher attempts failed (MCP config shape and missing prompt argument); the successful invocation passed a prompt argument and explicit empty MCP server map. The second review streamed progress visibly. No files were edited by the reviewer, and its review is static evidence, not a test result.

Review dispositions:

- Fixed transient DB-control errors aborting runs; local deadlines continue while control reads retry.
- Added bounded shutdown drain and safe allowlisted failure codes/class-only diagnostics. Error fallback and Boot executor coexistence were independently fixed during verification; the existing bearer-auth integration suite verifies `applicationTaskExecutor` and virtual threads.
- Accept nonblank finish metadata, including length, as terminal; empty answers and EOF without terminal metadata fail. Partial tool calls at a length boundary cannot execute. This follows the reviewed Onyx answer path without adding a truncated-status model.
- Reject missing provider configuration and oversized context before message reservation. Refresh configuration-backed builtin Persona on a new send, including existing sessions; DB defines the authoritative deadline. JVM/DB clock synchronization remains necessary for local cancellation timing.
- Constrain the current options converter to the GPT-5 family; gpt-5-mini is the tested provider baseline. Other model families need their matching native converter and verification with the settings consumer.
- Reconciled all six phases: API Redis dependency/timeouts, blocking-tool Stop/timeout acceptance, regenerate migration/assistant-only reservation, native ModelProvider selection, authenticated-sharing tradeoff and explicit decision before summary compression. No new worker, config journal, Conversation store or inference engine was added.

The reviewer suggested optional cleanup of tool-call context, the test convenience finalizer, a deadline read and an index. These are not prerequisites or new scope. The current tool context carries server-owned Actor/Tenant/run identity into the native runner; real tool consumers and their authorization/cancellation acceptance remain Phase 3.

Verification receipts and limits:

- `gradlew.bat clean check --no-daemon --console=plain` passed in 4m51s: 242 passed (core 152, API 51, worker 22, connector 17), no failures/errors; one optional live Chat check and three live Docling checks skipped. Final follow-up `:core:test --tests '*ChatTurnServiceTest' :api:test --tests '*ChatSessionApiIntegrationTest' --tests '*OpenApiContractTest'` with the live Chat flag and Infisical dev passed in 1m40s: 15 tests, no skips/failures. This covers the additional pre-reservation provider test, removal of synchronous retry after shutdown drain, final API/native execution and real model, plus OpenAPI drift. JetBrains build passed again on the final code.
- Actual OpenAI call through product send → native runner → persisted history/usage passed using explicitly enabled `realProviderRunsThroughSendNativeRunnerAndPersistedHistory` and Infisical dev `SPRING_AI_OPENAI_API_KEY`. IAM principal/discovery are test fixtures; this is not browser login or staging acceptance.
- OpenAPI generation and drift check passed. A native SDK transitive non-Jakarta Swagger annotations jar shadowed Springdoc's Jakarta annotations; excluding only that duplicate artifact fixes the reproduced `$dynamicRef` linkage failure.
- Frontend `corepack pnpm generate:api` and `corepack pnpm check` from `web/` passed with the repository-pinned pnpm 11.22.0: 52 unit tests, lint/format/types, client/route drift and production build. Invoking from the root initially selected global pnpm 11.24.0; no dependency was changed to accommodate that environment mismatch.
- JetBrains build passed. All edited Java/configuration files were inspected with warnings enabled. Remaining inspections include the private owned `Active` monitor, an existing inverted predicate suggestion and test assertion suggestions. IDE reports unresolved cross-module YAML imports and the `@Value` provider test property; actual resources exist and full API startup tests resolve them. No fully clean IDE result is claimed.

Raw CLI review output, launcher scripts, probe receipts and logs remain in ignored `.tmp/mem11-phase1/`. Durable contracts and test mappings are in [Chat spec](../../../specs/chat.md) and [Chat tests](../../../tests/chat.md).

Local documentation links and `git diff --check` passed. Work remains uncommitted; no production/staging database was mutated.

## Phase 2.1 — 2026-09-09

Implemented in the working tree, not committed/deployed as Chat: Persona/session/message migration V18, private session create/list/detail/history, IAM membership write guard, internal reservation/idempotency/finalization and generated browser clients. [Contract](../../../specs/chat.md), [test matrix](../../../tests/chat.md) and [plan](plan.md) define the boundary. Phase 1 scratch probes are recorded separately in [phase-1-verification.md](phase-1-verification.md).

| Verification | Result |
| --- | --- |
| Focused compile: core/api/worker through the checked-in wrapper | PASS |
| New Chat PostgreSQL contracts | 8 tests PASS: private ownership, singleton Tenant filtering, duplicate send, selected branch, rollback/constraints, terminal race/deadline and concurrent membership revoke |
| Full API context with PostgreSQL/security filters | 2 Chat tests PASS: create/list/reload/history and owner denial; session/CSRF/header/input rejection; no send endpoint exposed |
| OpenAPI generation then normal drift check | PASS; session/message required fields and nullable history fields are generated |
| `gradlew.bat clean check --no-daemon --console=plain` | PASS in 4m34s: core 140, API 45, worker 22, connector 17 passed; 3 optional live Docling tests skipped |
| `pnpm generate:api` then `pnpm check` in web | PASS: client drift, lint/format, TypeScript, 52 unit tests and production build |
| Local docs links and `git diff --check` | PASS |

The initial cross-Tenant test incorrectly attempted to create a second Tenant; the existing deployment singleton constraint rejected it. The corrected test checks the wrong Tenant ID against the actual scoped repository query without weakening the singleton. API tests were aligned with the existing CSRF token/header and 403 revoked-membership filter; no security filter was relaxed to pass tests.

JetBrains inspection tools were unavailable in this execution session; no IDE-clean result is claimed. Gradle compilation, Modulith/ArchUnit and real PostgreSQL/full API-context checks provide the current verification. API tests inject the established Actor principal and use synthetic OIDC discovery; this does not certify a live Keycloak login or deployed runtime. No browser Chat screen or model execution exists yet. Internal callbacks are tested directly, not exposed as a temporary send mode. Phase 2.2 owns dispatch, model failures/metadata, cancellation delivery, stale-run cleanup and resource lifecycle.

QA logs remain in ignored `.tmp/`; Testcontainers owns and reaps its test infrastructure. No production/staging database was changed. Only the independent skill/MCP installation was committed and pushed to main (`fb835f92f21d3c1a42d6381e7ca8dbb66edaff02`, CI success); Chat changes remain uncommitted.

## Original reply idempotency follow-up — 2026-09-09

The selected-branch regression reproduced the defect before the fix: retry returned the replacement assistant ID instead of the originally reserved ID. USER rows now retain an internal `original_assistant_message_id`; request lookup returns that reference without consulting latest-child selection. No API response field, endpoint, ChatRun table or JPA conversion was introduced. V18 is still an uncommitted, undeployed migration previously exercised only on disposable test databases; this change updates that initial migration, not the checksum of a retained deployed database.

Focused `:core:test --tests '*ChatPersistenceIntegrationTest' --no-daemon --console=plain` passed (8 tests, 39s). The branch test changes selection, starts a continuation and retries the original command: original user/assistant IDs return, row count and selected history stay unchanged, and a changed command still conflicts. Database tests also reject a missing or cross-session original reply reference. These are direct persistence scenarios; they do not establish a regenerate UI or model execution acceptance.

JetBrains MCP was available for this follow-up. Both edited Java production files and the edited test were inspected with warnings enabled: no errors or unresolved Java references; one existing naming suggestion remains on the positive `onSelectedBranch` predicate because its callers negate it for rejection checks. SQL inspection cannot resolve external migration tables without an IDE data source; the actual Flyway migration and constraints passed against PostgreSQL. No fully clean SQL IDE result is claimed.

Full repository gate `gradlew.bat clean check --no-daemon --console=plain` passed in 3m58s: 224 passed, zero failures/errors, 3 optional live Docling tests skipped (core 140, API 45, worker 22, connector 17 passed). This includes full API-context/security and OpenAPI drift checks against the revised schema. Documentation links/code fences and `git diff --check` passed. Raw red/green/gate logs stay under ignored `.tmp/mem11-idempotency-*.log`. The previous frontend result above remains prior evidence; frontend code and public contracts are unchanged by this follow-up. No commit or deployment was performed.
