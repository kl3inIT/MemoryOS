# Chat verification matrix

| Contract | Test and boundary |
| --- | --- |
| Stable server message IDs, UUID request identity, duplicate replay, EOF cursor resume and one send | `web/src/features/chat/chat-transport.test.ts`; production transport/generated client with HTTP response fixtures |
| Reset/gap history fallback, partial FAILED, committed Stop and complete/cancel race | `chat-transport.test.ts`; no model re-execution on recovery |
| Stream 401/403/404 fail closed; reject foreign-run events and nonadvancing history cursor | `chat-transport.test.ts` |
| Native composer Enter/Shift+Enter/IME, markdown/code/copy, multiple turns and reload | `web/tests/e2e/chat.spec.ts`; real browser and incremental HTTP fixture, native runtime and generated clients |
| New-session URL promotion preserves the composer and one SSE reader without a history reload; New chat/back opens the correct conversation | `chat.spec.ts`: `keeps the new conversation mounted through server ID promotion and resets only when switching` |
| Thinking/search waiting has one indicator, no empty Markdown cursor and no Copy action; Stop clears waiting | `chat.spec.ts`: waiting and grounded-waiting browser cases; Copy becomes available once real answer text exists |
| Reload RUNNING, Stop/partial, reconnect, missing buffer, provider failure, mobile drawer, reader cleanup and denied stream | `chat.spec.ts`; fixture owns execution/identity, so these tests do not certify real provider or Keycloak |
| Base welcome/composer layout, searchable authorized model picker, keyboard/mobile focus, chosen ID across reload, empty/error/fallback catalog | `chat.spec.ts`; generated catalog/send clients with HTTP fixtures |
| Validated sources/progress through native SDK metadata, stream split/gap/Stop/failure/history, fixed per-send model ID | `chat-transport.test.ts`; no second inference or parallel message store |
| Citation hover/focus/Escape, prose-only links, multi-source right panel, reader/back/close, mobile drawer bounds/focus and unavailable source retaining answer | `chat.spec.ts`; actual browser with synthetic document API; Search preview regression in `search.spec.ts` |
| Repeat code copy, fallback notice surviving new-session navigation but clearing on reload, and earlier context staying in view | `chat.spec.ts`; real browser with synthetic responses |
| Source range/provenance bounds before rendering history; nullable typed search-event source | `chat-transport.test.ts`; `OpenApiContractTest` exercises the generated runtime contract |
| Real OIDC MEMBER login, live model send/save/reload, RUNNING reload/Stop with partial, cross-Actor denial | [Phase 2.4 verification](../increments/active/mem-11-production-chat/verification.md#phase-24--2026-09-09); isolated PostgreSQL, normal API configuration and managed dev services; temporary realm users removed |
| Private session/root, default Persona identity, owner/Tenant filtering and inactive membership | `ChatPersistenceIntegrationTest.createsPrivateSessionWithOneRootAndSharedDefaultPersona` against production Flyway migrations/PostgreSQL |
| Same-command identity, conflict for changed command, pagination, retained partial outcome | `reservesPairOnceAndPreservesHistoryCursorAndPartialOutcome` |
| Concurrent duplicate send and one complete/cancel winner | `serializesConcurrentSendsAndTerminalWinners` with independent transactions |
| Cross-session parent/cursor and atomic rollback | `rejectsCrossSessionParentAndCursorAndRollsBackWholePair` |
| DB rejects cross-session parent, indirect latest-child, duplicate root, second RUNNING reply and missing/cross-session original reply reference | `databaseEnforcesParentSelectedChildRootAndSingleActiveReply` |
| Selected branch retains old answers, rejects off-branch cursor; retry returns original message IDs after switching branch and continuing, without adding rows or changing selection | `selectedBranchSkipsOlderAnswersWithoutDeletingThem`; direct persistence fixture, not a regenerate endpoint |
| No successful completion after deadline | `deadlineRejectsLateCompletionAndRetainsPartialFailure` |
| Write waits for concurrent IAM revoke and revalidates | `membershipGuardWaitsForRevocationAndThenDeniesWrite` using existing IAM Tenant lock |
| Admission reads one model/revision snapshot; a builtin Persona edit by another owner waits until the locked settings read completes | `ChatPersistenceIntegrationTest.builtinPersonaSnapshotBlocksAnotherOwnersEditUntilAdmissionReadCompletes` with independent PostgreSQL transactions |
| Shared access polling does not reload transcript pages; revoked access hides the view; malformed sources fail inside the query boundary | `chat-workspace.spec.ts` access-polling and malformed-source browser cases |
| Reopening sharing cannot submit its cached revision while the authoritative read is pending | `chat-workspace.spec.ts` delayed-refetch browser case |
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
| Empty final text fails; late execution cannot overwrite the DB winner or emit false completion | `ChatSessionApiIntegrationTest.emptyFinalAnswerFailsInsteadOfSavingFalseCompletion`, `lateCompletionCannotOverwritePersistedDeadlineOutcome`; terminal frames are read over the real random-port HTTP server, not MockMvc's asynchronously mutated response |
| Per-turn provider binding preserves native converter/metadata and isolates accounting across two runs | `ChatModelBindingTest`; `ChatSessionApiIntegrationTest.alternateNativeBindingUsesSameExecutorWithIsolatedPerTurnAccounting`; fixture is not certification of another provider |
| Terminal SSE waits for DB success, retry publishes the authoritative status | `ChatTurnServiceTest.terminalEventWaitsForCommitAndUsesDatabaseWinner` |
| Replay/live sequence, Unicode chunks, eviction/TTL, missing prefix and bounded reader/buffer admission | `StreamBufferWriterTest` |
| Flux disconnect/timeout releases reader, writer continues, slow reader with no demand resets | `ChatEventStreamTest` |
| SSE owner checks, cursor validation, serialization and committed outcome replay | `ChatSessionApiIntegrationTest.sseReplaysCommittedOutcomeAndChecksOwnerAndCursor`; rejection assertions use MockMvc, while full/resumed SSE and buffering headers use the real HTTP server with the existing signed-token fixture |
| Real HTTP through pinned product Nginx: text before terminal, disconnect/reconnect, local Stop/partial and one model execution | `ChatSessionApiIntegrationTest.nginxHttpDisconnectReconnectAndStopUseIndependentExecution`; JWT/JWKS and model transport are fixtures, Spring MVC/native execution/PostgreSQL/proxy are real |
| Generated required/nullable contracts and API surface | `OpenApiContractTest`; frontend generated-client drift/type checks |
| Closed capability and persistence ownership | `ModulithArchitectureTest`, `CoreDependencyRulesTest` |

API tests use synthetic Actor/OIDC fixtures; the SSE replay/deadline and Nginx tests authenticate signed JWTs through the actual resource-server filter and binding/membership lookup. They do not certify live Keycloak browser login. The optional live-model check delegates to the product provider composition. Browser Chat acceptance remains Phase 2.4. Missing-buffer/expiry tests establish the reset/fallback contract, not process recovery or token durability. No two-replica deployment or load acceptance is claimed. Phase 1 probes are not substituted for product checks; see the [current plan](../increments/active/mem-11-production-chat/plan.md).

## Editors, projects, assistants and sharing (V36)

| Contract | Verification |
| --- | --- |
| Persona/Project ownership, instruction precedence including empty custom instructions, admission snapshot, stale revisions and deletion preserving history | `ChatPersistenceIntegrationTest.projectsAndPersonasApplyAtAdmissionAndPreserveHistoryAcrossEditsAndDeletion` |
| Persona source restriction rechecks authority on a later tool call; revoking the last selected source must not widen to other readable sources | `SearchToolTest.personaSourceSelectionRechecksRevocationWithoutWideningToOtherReadableSources` |
| EDIT siblings, ASSISTANT-only regeneration, immutable old branches, selected history and command replay/input identity | `ChatPersistenceIntegrationTest.editAndRegenerateKeepOldBranchesAndCommandIdentityWithoutDuplicatingUserMessages` |
| Authenticated same-Tenant sharing, owner-only writes, output-specific feedback, revocation and deletion/late completion | `ChatPersistenceIntegrationTest.sharingIsReadOnlyTenantAccessFeedbackStaysOnOutputAndDeletionWinsLateCompletion`; `ChatSessionApiIntegrationTest.editorsProjectsPersonasSharingAndFeedbackRoundTripThroughAuthenticatedHttp` |
| Real HTTP creates, configures, sends/edits/regenerates, selects branches, renames, rates, shares/revokes and deletes; a failed combined configuration rolls back all settings | `ChatSessionApiIntegrationTest.editorsProjectsPersonasSharingAndFeedbackRoundTripThroughAuthenticatedHttp`: actual Spring Boot port, signed synthetic identity, PostgreSQL and native runner; model responses are synthetic |
| Spring Data provider/model JSONB and scoped collection mappings, revision advancement, cross-Tenant constraints, default protection, deletion and ORM/JDBC rollback | `ModelCatalogConstraintsTest`; existing catalog API integration scenarios |
| Browser versions, feedback per version, reload, shared read-only UI, revocation and deletion | `web/tests/e2e/chat-workspace.spec.ts` |
| Assistant form saves/reloads sources, starters and limits; Project CRUD/instructions and retained conversations; mobile layout | `web/tests/e2e/chat-workspace.spec.ts`; existing `chat.spec.ts` mobile/stream regression |

Browser tests use synthetic HTTP fixtures and isolate the UI contract. They do not certify a live provider, source relevance or deployed IAM. [Editor verification](../increments/active/mem-11-production-chat/editor-verification.md) records current checks and remaining deployment/acceptance boundaries.

## Model catalog persistence

| Contract | Verification |
| --- | --- |
| Tenant provider/model/default/association FKs | `ModelCatalogConstraintsTest` (adversarial fixture relaxes only the single-Tenant deployment slot) |
| BYOK encryption, IV uniqueness, tamper and owner binding, KEEP/REMOVE | `ProviderCredentialsTest` |
| Same client reuse, configuration retirement, active leases and capacity/shutdown | `ChatModelClientsTest` |
| Slow client initialization/cleanup does not block cached model leases; initialization failure releases capacity for retry | Concurrent cases in `ChatModelClientsTest` |
| Explicit deployment capabilities override name defaults; finite cost budget fails startup without pricing | `ChatModelCatalogConfigurationTest` |
| Null option keys/values are validation errors and valid options are copied | `ModelSettingsTest`; `ChatSessionApiIntegrationTest.nullModelOptionsReturnBadRequest` verifies HTTP 400 |
| Validation schema is distinct from Search; every unsafe browser operation declares the CSRF header | `OpenApiContractTest` and generated-client checks |
| Native standard/completion-token conversion, reasoning, rejected options and unknown pricing | `OpenAiChatProviderAdapterTest` |
| Dedicated authority, redaction, stale writes, same-name model selection and idempotency | Catalog cases in `ChatSessionApiIntegrationTest` |
| Persona precedence, fallback, Group revoke, Persona restrictions on manager, default protection | Catalog cases in `ChatSessionApiIntegrationTest` |
| Old turn retains options; next turn uses new settings | `changingModelOptionsWhileRunningAffectsOnlyTheNextTurn` |
| Second adapter without executor changes or fake credentials | `secondRegisteredAdapterNeedsNoExecutorChangesOrDummyCredentials` |
| Bearer-authenticated HTTP → native OpenAI SDK → transcript/usage, no capability probe | `configuredProviderRunsThroughAuthenticatedHttpNativeSdkAndPersistedOutcome` (local provider/issuer fixtures) |
| Explicit validation hides provider exceptions and handles trailing usage frames | `defaultsCannotBeHiddenDeletedOrRevokedAndValidationDoesNotExposeProviderErrors` |

Current scope and limits: [catalog spec](../specs/chat-models.md). New local provider acceptance must use its real endpoint; the fixture adapter does not establish its behavior.


## Onyx Search parity and latency (Phase 3.3)

| Contract | Verification |
| --- | --- |
| Duplicate contributions sum, k=50, deterministic rank/query tie breaks, grouping before candidate bounds | `DocumentSearchServiceTest`, `SearchToolTest` |
| First-call rewrite, later same-source query omission, expansion reuse on a new source type, per-turn state | `SearchToolTest` |
| Three-chunk representatives, full section boundaries, neighbor classification, overlap merge and bounded context | `SearchToolTest` |
| Minimum supported helper reasoning after native conversion; answer options unchanged | `OpenAiChatProviderAdapterTest`, including actual SDK HTTP request serialization |
| Helper deadline covers native attempts; cooperative cancellation drains native work before terminal usage persistence | `SearchTasksTest`; `ChatSessionApiIntegrationTest.stopInterruptsNativeTypedHelperWorkAndDrainsItBeforePersistingTheOutcome` |
| Provider ignores interrupts: bounded Stop, no late evidence/retry/accounting write, client and capacity retained until actual completion | `SearchTasksTest`; `SearchToolTest.closeBoundsUncooperativeRetrievalAndRejectsItsLateEvidence`; `ChatTurnServiceTest.terminalOutcomeRetainsClientAndCapacityUntilActualWorkDrains`; native API `stopPersistsWithinCleanupBoundWhenNativeProviderIgnoresInterrupts` |
| Long reading titles preserve full citations; malformed optional metadata leaves authorized documents in mixed batches | `SearchToolTest.longTitleIsBoundedOnlyInReadingProgressAndPreservesFullCitationTitle`; `SourceSearchMetadataMigrationTest` |
| Concurrent native admission includes in-flight token/cost reservations; ledger retains sole usage ownership | `ChatModelGuardTest` |
| Upload dates differ from operational dates, V34→V35/idempotency, multi-mapping metadata pairing and fresh authorization/revocation | `SourceSearchMetadataMigrationTest` |
| Nested source/date filters on both hybrid branches, legacy metadata readiness repair and unchanged-vector reuse | `OpenSearchRetrievalIntegrationTest` |
| One alias/config prefetch and unique embedding batch; bounded parallel hybrid IO; fresh generation/authorization batches up to 1,000 IDs | `OpenSearchRetrievalIntegrationTest`, `DocumentSearchServiceTest` |
| Query/filter and reading-document progress precede evidence; duplicate replay retains state; Stop clears progress | `chat-transport.test.ts`; 23 Chromium Chat scenarios including the new progress case |
| Actual worker starts and processes files with retrieval observations wired | `WorkerFileProcessingIntegrationTest` |

The opt-in `realCorpusMeasuresNativeSearchCyclesFirstTextAndTotalThroughHttpSse` uses an authorized local snapshot, real OpenSearch, live embeddings/provider, PostgreSQL transcript and HTTP SSE. It records each Search cycle, first text, total duration and nullable native usage, and checks revenue/travel facts plus the long Word document's cited role coverage. Model-selected tool counts are observations, not fixed assertions. The snapshot is the authority fixture; real SQL permissions and multi-source/date behavior are covered separately above. See [measured results and limits](../increments/active/mem-11-production-chat/latency-verification.md). No corpus or answer receipts are checked in.

## Grounded backend (Phase 3.1)

| Contract | Verification |
| --- | --- |
| Native named tool binding, argument bounds, invalid selection fallback, overlap/adjacency merge, stable evidence numbers and context bounds | `SearchToolTest` |
| Follow-up rewrite receives history, rewrites cache per turn, query weights remain distinct; context classification sees real neighbors and can reject a misleading subject | `SearchToolTest.followUpRewritesUseHistoryAndAreCachedWhileToolQueriesKeepTheirOwnWeight`; `classificationReadsNeighborsBeforeRejectingTheWrongSubject` |
| FULL_DOCUMENT reads at most five neighbors per side; Stop during concurrent rewriting cancels sibling work and prevents retrieval | `SearchToolTest.fullDocumentClassificationFetchesOnlyTheWiderBoundedWindow`; `stopDuringQueryRewritePreventsKeywordInferenceAndRetrieval` |
| Same native process records typed selection and streaming usage once; denied content never reaches either model prompt | `ChatSessionApiIntegrationTest.nativeSearchToolSelectsExpandsStreamsSourcesAndPersistsTypedAndStreamingUsageOnce` |
| Stop interrupts a blocking retrieval on a virtual thread and prevents later queries/tools/inference while retaining partial text | `ChatSessionApiIntegrationTest.stopInterruptsBlockingRetrievalOnVirtualThreadAndPreventsFurtherToolsAndInference` |
| Tool content is included in context limits; native typed usage is not double counted; unknown usage stays unknown and native budget stops inference | `ChatModelGuardTest` |
| Citation reminders follow actual evidence; final-cycle request explains tools are unavailable; transcript remains unchanged | `ChatModelGuardTest.citationReminderTracksAvailableEvidenceWithoutMutatingConversationMessages`; `lengthIsTerminalAndKnownUsageIsRecordedOnceWithToolsOff` |
| Sources commit with the terminal winner and survive historical source absence; other conversation owners are denied | `ChatPersistenceIntegrationTest.sourcesCommitWithTheTerminalWinnerAndRemainHistoricalEvidence` |
| Sources share replay order with text/outcome, preserve tool/message identity, and release readers | `ChatEventStreamTest.searchEvidenceReplaysBeforeTextAndTerminalWithStableWireIdentity` |
| Native observation start/error/stop handlers cannot see payload/result/error text but retain tool identity | `ChatObservationSanitizerTest` |

Opt-in `MEMORYOS_CHAT_GROUNDING_LIVE_TEST=true` runs `ChatSessionApiIntegrationTest.realGroundedAnswersHandleNeighborsFollowUpMissingEvidenceAndDocumentInjection` with an actual provider and a controlled synthetic retrieval corpus through the native runtime and real transcript database. It checks a fact found in neighbors rather than the matching chunk, the wrong subject, follow-up reference resolution, missing evidence, citation IDs and document prompt injection. Synthetic answer receipts are written only by this opt-in test under ignored `api/build/reports/chat-grounding/` for manual inspection; global stdout/stderr capture stays disabled. Its pass/fail receipt belongs in the increment verification; it is not a retrieval relevance benchmark or browser acceptance. Real OpenSearch is verified separately in Retrieval. Browser citations/reload now have fixture coverage in Phase 3.2. Broader real-corpus quality and integrated provider/index/browser acceptance remain outstanding.
