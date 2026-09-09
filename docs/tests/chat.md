# Chat verification matrix

| Contract | Test and boundary |
| --- | --- |
| Stable server message IDs, UUID request identity, duplicate replay, EOF cursor resume and one send | `web/src/features/chat/chat-transport.test.ts`; production transport/generated client with HTTP response fixtures |
| Reset/gap history fallback, partial FAILED, committed Stop and complete/cancel race | `chat-transport.test.ts`; no model re-execution on recovery |
| Stream 401/403/404 fail closed; reject foreign-run events and nonadvancing history cursor | `chat-transport.test.ts` |
| Native composer Enter/Shift+Enter/IME, markdown/code/copy, multiple turns and reload | `web/tests/e2e/chat.spec.ts`; real browser and incremental HTTP fixture, native runtime and generated clients |
| Reload RUNNING, Stop/partial, reconnect, missing buffer, provider failure, mobile drawer, reader cleanup and denied stream | `chat.spec.ts`; fixture owns execution/identity, so these tests do not certify real provider or Keycloak |
| Real OIDC MEMBER login, live model send/save/reload, RUNNING reload/Stop with partial, cross-Actor denial | [Phase 2.4 verification](../increments/active/mem-11-production-chat/verification.md#phase-24--2026-09-09); isolated PostgreSQL, normal API configuration and managed dev services; temporary realm users removed |
| Private session/root, default Persona identity, owner/Tenant filtering and inactive membership | `ChatPersistenceIntegrationTest.createsPrivateSessionWithOneRootAndSharedDefaultPersona` against production Flyway migrations/PostgreSQL |
| Same-command identity, conflict for changed command, pagination, retained partial outcome | `reservesPairOnceAndPreservesHistoryCursorAndPartialOutcome` |
| Concurrent duplicate send and one complete/cancel winner | `serializesConcurrentSendsAndTerminalWinners` with independent transactions |
| Cross-session parent/cursor and atomic rollback | `rejectsCrossSessionParentAndCursorAndRollsBackWholePair` |
| DB rejects cross-session parent, indirect latest-child, duplicate root, second RUNNING reply and missing/cross-session original reply reference | `databaseEnforcesParentSelectedChildRootAndSingleActiveReply` |
| Selected branch retains old answers, rejects off-branch cursor; retry returns original message IDs after switching branch and continuing, without adding rows or changing selection | `selectedBranchSkipsOlderAnswersWithoutDeletingThem`; direct persistence fixture, not a regenerate endpoint |
| No successful completion after deadline | `deadlineRejectsLateCompletionAndRetainsPartialFailure` |
| Write waits for concurrent IAM revoke and revalidates | `membershipGuardWaitsForRevocationAndThenDeniesWrite` using existing IAM Tenant lock |
| Real session HTTP create/list/reload/history, owner denial and membership revoke | `ChatSessionApiIntegrationTest` with full API context/PostgreSQL and existing security filters |
| Missing authentication/CSRF and input bounds on session/send operations | `ChatSessionApiIntegrationTest` |
| Native runner send, usage persistence, same-request retry without another inference, partial EOF and local Stop | `ChatSessionApiIntegrationTest`; model transport mocked, native framework/IAM/PostgreSQL execute |
| Cross-owner Stop denied, cancel committed before completion wins, expired rows cannot be resurrected | `ChatPersistenceIntegrationTest` |
| Stop before task starts, admission before reservation and rejected dispatch cleanup | `ChatTurnServiceTest` |
| Real OpenAI transport through product send/native execution/persistence | `ChatSessionApiIntegrationTest.realProviderRunsThroughSendNativeRunnerAndPersistedHistory`; explicitly enabled with MEMORYOS_CHAT_LIVE_TEST=true and SPRING_AI_OPENAI_API_KEY |
| DB control outage does not cancel a healthy turn; shutdown drains partial from a virtual task | `ChatTurnServiceTest.transientControlFailureDoesNotCancelAnOtherwiseHealthyTurn`, `shutdownWaitsForCanceledVirtualTaskToPersistPartial` |
| Oversize question rolls back before tree mutation; existing session refreshes builtin configuration | `ChatPersistenceIntegrationTest.oversizedQuestionRollsBackBeforeTreeAdvancesAndBuiltinConfigRefreshesOnSend` |
| Newest-first context truncation and orphan assistant removal | `ChatTurnSetupTest` |
| Final cycle tools off, length finish, missing metadata and usage preserved before provider error | `ChatModelGuardTest` at the product ChatModel boundary |
| Empty final text fails; late execution cannot overwrite the DB winner or emit false completion | `ChatSessionApiIntegrationTest.emptyFinalAnswerFailsInsteadOfSavingFalseCompletion`, `lateCompletionCannotOverwritePersistedDeadlineOutcome` |
| Per-turn provider binding preserves native converter/metadata and isolates accounting across two runs | `ChatModelBindingTest`; `ChatSessionApiIntegrationTest.alternateNativeBindingUsesSameExecutorWithIsolatedPerTurnAccounting`; fixture is not certification of another provider |
| Terminal SSE waits for DB success, retry publishes the authoritative status | `ChatTurnServiceTest.terminalEventWaitsForCommitAndUsesDatabaseWinner` |
| Replay/live sequence, Unicode chunks, eviction/TTL, missing prefix and bounded reader/buffer admission | `StreamBufferWriterTest` |
| Flux disconnect/timeout releases reader, writer continues, slow reader with no demand resets | `ChatEventStreamTest` |
| SSE owner checks, cursor validation, serialization and committed outcome replay | `ChatSessionApiIntegrationTest.sseReplaysCommittedOutcomeAndChecksOwnerAndCursor` |
| Real HTTP through pinned product Nginx: text before terminal, disconnect/reconnect, local Stop/partial and one model execution | `ChatSessionApiIntegrationTest.nginxHttpDisconnectReconnectAndStopUseIndependentExecution`; JWT/JWKS and model transport are fixtures, Spring MVC/native execution/PostgreSQL/proxy are real |
| Generated required/nullable contracts and API surface | `OpenApiContractTest`; frontend generated-client drift/type checks |
| Closed capability and persistence ownership | `ModulithArchitectureTest`, `CoreDependencyRulesTest` |

API tests use synthetic Actor/OIDC fixtures; the Nginx test authenticates signed JWTs through the actual resource-server filter and binding/membership lookup. They do not certify live Keycloak browser login. The optional live-model check delegates to the product provider composition. Browser Chat acceptance remains Phase 2.4. Missing-buffer/expiry tests establish the reset/fallback contract, not process recovery or token durability. No two-replica deployment or load acceptance is claimed. Phase 1 probes are not substituted for product checks; see the [current plan](../increments/active/mem-11-production-chat/plan.md).

## Backend model catalog

| Contract | Verification |
| --- | --- |
| Tenant provider/model/default/association FKs | `ModelCatalogConstraintsTest` (adversarial fixture relaxes only the single-Tenant deployment slot) |
| BYOK encryption, IV uniqueness, tamper and owner binding, KEEP/REMOVE | `ProviderCredentialsTest` |
| Same client reuse, configuration retirement, active leases and capacity/shutdown | `ChatModelClientsTest` |
| Native standard/completion-token conversion, reasoning, rejected options and unknown pricing | `OpenAiChatProviderAdapterTest` |
| Dedicated authority, redaction, stale writes, same-name model selection and idempotency | Catalog cases in `ChatSessionApiIntegrationTest` |
| Persona precedence, fallback, Group revoke, Persona restrictions on manager, default protection | Catalog cases in `ChatSessionApiIntegrationTest` |
| Old turn retains options; next turn uses new settings | `changingModelOptionsWhileRunningAffectsOnlyTheNextTurn` |
| Second adapter without executor changes or fake credentials | `secondRegisteredAdapterNeedsNoExecutorChangesOrDummyCredentials` |
| Bearer-authenticated HTTP → native OpenAI SDK → transcript/usage, no capability probe | `configuredProviderRunsThroughAuthenticatedHttpNativeSdkAndPersistedOutcome` (local provider/issuer fixtures) |
| Explicit validation hides provider exceptions and handles trailing usage frames | `defaultsCannotBeHiddenDeletedOrRevokedAndValidationDoesNotExposeProviderErrors` |

Current scope and limits: [catalog spec](../specs/chat-models.md). New local provider acceptance must use its real endpoint; the fixture adapter does not establish its behavior.
