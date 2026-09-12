# Chat verification matrix

Renderer/presentation coverage: `code-renderers.test.tsx` exercises real Shiki/Mermaid, streaming fallback, whitespace, malformed/oversized diagrams and dialog focus. `chat-artifacts.test.tsx` validates the read-only allowlist and desktop/mobile panels. `chat-transport.test.ts` checks one authorized metadata read, no second inference and restored history. `ChatArtifactTest` checks native tool binding/bounds/sealing; `ChatTurnSetupTest` checks bounded follow-up context; persistence tests retain the terminal winner; `ChatSessionApiIntegrationTest.nativePresentationToolPersistsThroughAuthorizedHistoryAndAdvertisesTerminalMetadata` exercises actual native tool execution, saved HTTP history, denied foreign reads and the SSE flag. English/VI browser cases exercise runtime renderers, drafts and reload. Live provider quality is separate.

| Contract | Test and boundary |
| --- | --- |
| One-time automatic naming uses native model; owner/CSRF denial, repeat requests, original answer and manual title preserved | `ChatSessionApiIntegrationTest.automaticTitleUsesNativeProviderOnceAndKeepsAnswerAndManualRename`: Spring API/native runner/real PostgreSQL, synthetic provider |
| Naming only after completed answer, concurrent/manual rename wins even with the same text | `ChatPersistenceIntegrationTest`: real PostgreSQL title claim and conditional write |
| Unicode-safe short fallback, compact picker, separate full management dialog and character citation highlighting | `chat-transport.test.ts`, `chat-file-reader.test.tsx`: UI/unit contracts |
| File citation positions distinguish indexed passages; private reader rejects changed generations | `FileReaderToolTest`, `DocumentSearchServiceTest`: evidence/service boundary with controlled dependencies |
| Live model reads image-only randomized code and geometric counts; native usage and persisted file/citation identity | Opt-in `ChatSessionApiIntegrationTest.realVisionReadsPixelsThroughAuthenticatedHttpAndPersistedHistory`: real authenticated HTTP/native adapter/OpenAI gpt-5-mini/DB, storage double and seeded READY. Two image cases passed 2026-09-12; not upload/worker/browser E2E. [Measured evidence](../increments/completed/chat-attachments-production/verification.md#live-vision-và-đo-tài-nguyên--2026-09-12) |
| Vision HTTP data URL, non-vision marker without storage reads, saved image citation and file identity | Parameterized `ChatSessionApiIntegrationTest.configuredProviderRunsThroughAuthenticatedHttpNativeSdkAndPersistedOutcome`: authenticated HTTP API + native SDK + local HTTP provider, real DB; storage controlled and READY seeded, not live vision inference |
| Non-vision history/workspace, ordered images, Stop after private IO and over-budget image-only send | `ChatTurnSetupTest`: native setup/materialization |
| Search outage fallback preserves authorization/cancellation | `FileReaderToolTest`: per-turn scope/tool boundary, dependency doubles |
| Lost initiate/finalize responses reuse identity without a second PUT; HTTP denial and cancellation are not retried | `chat-attachments.test.tsx`: production upload orchestration with network/API doubles |
| Expired worker takeover fences late completion/failure; control plane expires abandoned upload without retry job | `ChatFileLifecycleIntegrationTest`: real PostgreSQL/object lifecycle, storage double |
| UserFile upload identity, same-tenant cross-owner denial, metadata mismatch and reauthorization after storage IO | `ChatFileLifecycleIntegrationTest`: real PostgreSQL/IAM/adoption; controlled storage IO |
| Duplicate finalize, claim fencing, Document reference protection, delete during parse and durable raw retirement | `ChatFileLifecycleIntegrationTest`: real repositories and transactions; no live extractor claim |
| File endpoints enforce CSRF/owner and map cap/checksum errors and async status | `ChatSessionApiIntegrationTest.fileUploadFinalizeAndDeletionRespectOwnerAndCsrf`, `filePolicyAndAdmissionRejectOverLimitAndMalformedChecksum`: full API/security + PostgreSQL, storage mocked |
| Plaintext Unicode ranges, deleted/non-owner denial, raw metadata mismatch and revocation/deletion during storage open close the stream | `ChatFileLifecycleIntegrationTest`: real PostgreSQL and owner checks; controlled object streams |
| Text cursor, no-store, authenticated attachment disposition, octet-stream/nosniff and unavailable raw/text endpoints | `ChatSessionApiIntegrationTest.fileUploadFinalizeAndDeletionRespectOwnerAndCsrf`: real API/security; extraction state seeded and raw storage mocked |
| Complete Unicode file context is retained before the corresponding question | `ChatTurnSetupTest.unicodeFileContentIsPlacedBeforeItsQuestionAndWorkspaceContent`: native message construction, not provider acceptance |
| Ordered file IDs through attachment-only send, replay, regenerate, edit and sharing; foreign file rejection rolls back | `ChatPersistenceIntegrationTest.orderedMessageFilesSurviveReplayRegenerationEditingAndSharedHistoryWithoutNewUploads`: real PostgreSQL/message transactions; READY file metadata seeded |
| Empty custom Persona overrides Project files; replay context can acquire required locks; revision updates preserve admitted snapshots | `ChatPersistenceIntegrationTest.customPersonaEmptyFileListOverridesProjectAndReloadContextDoesNotUseReadOnlyLocks`: real PostgreSQL/JPA/transaction boundary |
| Upload preview/removal cleanup, asynchronous readiness and stable file ID, Gửi/Enter blocked until READY, file-only message editing | `chat-attachments.test.tsx`: real assistant-ui runtime/adapter and production composer wrappers, mocked upload APIs |
| Message files use the source workspace's desktop panel/mobile modal; Escape restores the trigger, locale changes preserve the reader and composer draft, close releases private data | `chat-file-reader.test.tsx`: native assistant-ui runtime and real readers with API/layout doubles; Playwright CLI desktop/390px fixture inspection in MEM-74/22 verification |
| Feedback failure retains stable reason IDs and note through locale changes without automatically replaying a mutation | `chat-dialog.test.tsx`; existing feedback persistence/retry browser case in `chat-workspace.spec.ts` |
| Inert paged text, no cached private reader data after close, saved image URL cleanup, unavailable-file denial and selection preservation | `chat-file-reader.test.tsx`: real React Query/components, mocked APIs; deletion reuses the project confirmation dialog |
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
| Real OIDC MEMBER login, live model send/save/reload, RUNNING reload/Stop with partial, cross-Actor denial | [Phase 2.4 verification](../increments/completed/mem-11-production-chat/verification.md#phase-24--2026-09-09); isolated PostgreSQL, normal API configuration and managed dev services; temporary realm users removed |
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

API tests use synthetic Actor/OIDC fixtures; the SSE replay/deadline and Nginx tests authenticate signed JWTs through the actual resource-server filter and binding/membership lookup. They do not certify live Keycloak browser login. The optional live-model check delegates to the product provider composition. Browser Chat acceptance remains Phase 2.4. Missing-buffer/expiry tests establish the reset/fallback contract, not process recovery or token durability. No two-replica deployment or load acceptance is claimed. Phase 1 probes are not substituted for product checks; see the [current plan](../increments/completed/mem-11-production-chat/plan.md).

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
| Name-only Project creation opens its workspace; opening/typing creates no session; first send has projectId, one stream and no composer remount/history read | Project draft scenario in `chat-workspace.spec.ts`; generic first-send promotion in `chat.spec.ts` |
| Move/remove by keyboard menu and desktop drag/drop updates folder membership without inference; Project deletion unlinks and retains history | Project membership and deletion scenarios in `chat-workspace.spec.ts` |
| Rename Escape/focus restore, retained draft on error, header synchronization; inline edit failure preserves the UUID through retry | Draft recovery scenario in `chat-workspace.spec.ts` |
| Separate reactions retain drafts on error, survive reload and can be removed; feedback remains answer-version-specific | Feedback recovery and saved-branch scenarios in `chat-workspace.spec.ts` |
| Share/copy in one dialog, clipboard-denied manual fallback, native radio keyboard selection, focus restore and stale revision gating | Sharing scenarios in `chat-workspace.spec.ts` |
| Saved titles `Chat`/`Search` cannot select header mode; empty Chat and Search retain their mode menus | Saved-title mobile scenarios in `chat-workspace.spec.ts` |
| Malformed Project creation response retains the dialog/draft and exposes the error; loading assistant choices are not labeled unavailable, while a missing loaded choice is | Creation-response and delayed-settings scenarios in `chat-workspace.spec.ts` |
| Project-scoped session POST returns a created session, GET pagination remains distinct, and the saved session opens in Chat | Project-endpoint browser scenario in `chat-workspace.spec.ts`; synthetic fixture contract only |

Browser tests use synthetic HTTP fixtures and isolate the UI contract. They do not certify a live provider, source relevance or deployed IAM. [Editor verification](../increments/completed/mem-11-production-chat/editor-verification.md) records current checks and remaining deployment/acceptance boundaries.

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


## the retired reference implementation Search parity and latency (Phase 3.3)

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

The opt-in `realCorpusMeasuresNativeSearchCyclesFirstTextAndTotalThroughHttpSse` uses an authorized local snapshot, real OpenSearch, live embeddings/provider, PostgreSQL transcript and HTTP SSE. It records each Search cycle, first text, total duration and nullable native usage, and checks revenue/travel facts plus the long Word document's cited role coverage. Model-selected tool counts are observations, not fixed assertions. The snapshot is the authority fixture; real SQL permissions and multi-source/date behavior are covered separately above. See [measured results and limits](../increments/completed/mem-11-production-chat/latency-verification.md). No corpus or answer receipts are checked in.

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
