package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.BeforeToolCallContext;
import com.embabel.chat.ToolCall;
import com.embabel.common.ai.model.LlmOptions;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceType;
import io.memoryos.iam.TenantId;
import io.memoryos.retrieval.SearchSection;
import java.time.Duration;
import java.util.Map;
import com.embabel.chat.UserMessage;
import com.embabel.chat.AssistantMessage;
import io.memoryos.chat.ChatSearchEvent;
import io.memoryos.iam.ActorId;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchResults;
import io.memoryos.retrieval.SearchHit;
import io.memoryos.retrieval.SearchPage;
import io.memoryos.retrieval.SearchUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import reactor.core.publisher.Mono;

class SearchToolTest {
    private final DocumentSearchService search = mock(DocumentSearchService.class);
    private final PromptRunner runner = mock(PromptRunner.class);
    private final List<ChatSearchEvent> events = new ArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final UUID document = UUID.randomUUID();
    private final UUID generation = UUID.randomUUID();
    private final SearchHit first = hit(2);
    private final SearchHit second = hit(3);
    private final SearchSection section = new SearchSection(first, List.of(first, second));
    private final SourceSearchScope scope = new SourceSearchScope(new TenantId(UUID.randomUUID()), Map.of(UUID.randomUUID(), SourceType.FILE));

    @BeforeEach
    void rewrites() {
        when(search.scope(any())).thenReturn(scope);
        when(runner.getLlm()).thenReturn(new LlmOptions());
        when(runner.withLlm(any())).thenReturn(runner);
        when(runner.createObject(anyList(), eq(SearchTool.SemanticQuery.class))).thenReturn(new SearchTool.SemanticQuery("policy"));
        when(runner.createObject(anyList(), eq(SearchTool.KeywordQueries.class))).thenReturn(new SearchTool.KeywordQueries(List.of("policy")));
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenReturn(
                new SearchTool.ContextSelection(SearchTool.Expansion.MAIN_SECTION_ONLY));
    }

    private SearchTool tool(int availableTokens) {
        return tool(availableTokens, Duration.ofSeconds(5), false);
    }

    private SearchTool tool(int availableTokens, Duration timeout, boolean detectFilters) {
        var tool = new SearchTool(search, new ActorId(UUID.randomUUID()), runner, new JTokkitTokenCountEstimator(),
                new ChatSearchProperties(30, 10, 6000, 8000, 3, timeout, detectFilters, Duration.ofSeconds(1)), () -> {
                    if (stopped.get()) throw new CancellationException();
                }, () -> availableTokens, events::add, Mono.never(), List.of(new UserMessage("policy")), Instant.now().plusSeconds(60), new io.memoryos.retrieval.SearchTimings(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), io.micrometer.observation.ObservationRegistry.NOOP));
        tool.beforeToolCall(new BeforeToolCallContext(new ToolCall("tool-1", "searchKnowledge", "{}")));
        return tool;
    }

    private SearchHit hit(int ordinal) {
        return new SearchHit(document, generation, ordinal, "Policy", "text/plain", "Section " + ordinal, "[]", Instant.EPOCH, .9);
    }

    private SearchResults candidates() {
        var result = mock(SearchResults.class);
        when(result.hits()).thenReturn(List.of(first, second));
        when(result.sections()).thenReturn(List.of(section));
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenReturn(result);
        when(search.expand(eq(result), any(SearchSection.class), anyInt())).thenAnswer(call -> call.<SearchSection>getArgument(1).passages());
        return result;
    }

    @Test
    void invalidSelectionCannotIntroduceDocumentIdsAndFallsBackToMergedRankedEvidence() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(
                new SearchTool.Selection(List.of(999)));
        try (var tool = tool(8000)) {
            var response = tool.searchKnowledge(List.of("policy"), null);
            assertTrue(response.contains("[1] Policy\nSection 2\nSection 3"));
            assertFalse(response.contains("999"));
            var sources = events.stream().filter(e -> e.source() != null).toList();
            assertEquals(1, sources.size());
            var source = sources.getFirst().source();
            assertNotNull(source);
            assertEquals(2, source.startOrdinal());
            assertEquals(3, source.endOrdinal());
            assertEquals(response, tool.searchKnowledge(List.of("policy"), null));
            assertEquals(1, events.stream().filter(e -> e.source() != null).count());
            verify(search, times(2)).expand(any(), any(SearchSection.class), eq(2));
        }
    }

    @Test
    void noEvidenceSkipsSelectionAndUnavailableSearchIsDistinctFromNoMatches() {
        var result = mock(SearchResults.class);
        when(result.hits()).thenReturn(List.of());
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenReturn(result).thenThrow(new SearchUnavailableException());
        try (var tool = tool(8000)) {
            assertTrue(tool.searchKnowledge(List.of("policy"), null).startsWith("No authorized evidence"));
            assertTrue(tool.searchKnowledge(List.of("policy"), null).contains("unavailable"));
            verify(runner, never()).createObject(anyString(), eq(SearchTool.Selection.class));
            verify(search, never()).expand(any(), any(SearchSection.class), anyInt());
        }
    }

    @Test
    void contextBoundDoesNotEmitSourcesThatWereNotIncludedInTheToolResult() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(
                new SearchTool.Selection(List.of(1)));
        try (var tool = tool(1)) {
            assertEquals("No evidence fits the available context.", tool.searchKnowledge(List.of("policy"), null));
            assertTrue(events.stream().noneMatch(e -> e.source() != null));
        }
    }

    @Test
    void oversizedNeighborWindowShrinksAroundTheMatchingPassageAndKeepsExactProvenance() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(
                new SearchTool.Selection(List.of(1)));
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenReturn(
                new SearchTool.ContextSelection(SearchTool.Expansion.INCLUDE_ADJACENT_SECTIONS));
        when(search.expand(any(), eq(section), eq(2))).thenReturn(java.util.stream.IntStream.range(0, 5).mapToObj(i -> new SearchPage.Passage(i,
                        i == 2 ? "MATCHING PASSAGE" : "Distant context ".repeat(400), "[]")).toList());
        try (var tool = tool(90)) {
            String answer = tool.searchKnowledge(List.of("policy"), null);
            assertTrue(answer.contains("MATCHING PASSAGE"));
            assertFalse(answer.contains("Distant context"));
            var source = events.stream().filter(e -> e.source() != null).findFirst().orElseThrow().source();
            assertNotNull(source);
            assertEquals(2, source.startOrdinal());
            assertEquals(2, source.endOrdinal());
            assertEquals(1, source.provenance().size());
        }
    }

    @Test
    void stopDuringTypedSelectionDoesNotFallBackOrExpand() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenAnswer(ignored -> {
            stopped.set(true);
            throw new IllegalStateException("native binding failed");
        });
        try (var tool = tool(8000)) {
            assertThrows(CancellationException.class, () -> tool.searchKnowledge(List.of("policy"), null));
            verify(search, never()).expand(any(), any(SearchSection.class), anyInt());
            assertTrue(events.stream().noneMatch(e -> e.source() != null));
        }
    }

    @Test
    void nativeToolSchemaAndBindingUseNamedParametersAndRejectUnboundedArguments() {
        try (var tool = tool(8000)) {
            var nativeTool = Tool.fromInstance(tool).getFirst();
            assertEquals("searchKnowledge", nativeTool.getDefinition().getName());
            var result = nativeTool.call("{\"queries\":[\"a\",\"b\",\"c\",\"d\"]}");
            assertTrue(assertInstanceOf(Tool.Result.Text.class, result).getContent().contains("Invalid search arguments"));
            verifyNoInteractions(search);
        }
    }

    @Test
    void classificationReadsNeighborsBeforeRejectingTheWrongSubject() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of(1)));
        when(search.expand(any(), eq(section), eq(2))).thenReturn(List.of(new SearchPage.Passage(1, "This contract is for PROJECT Y, not PROJECT X.", "[]"),
                        new SearchPage.Passage(2, "The quoted fee is 100000.", "[]")));
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenAnswer(call -> {
            assertTrue(call.<String>getArgument(0).contains("PROJECT Y"));
            verify(search).expand(any(), eq(section), eq(2));
            return new SearchTool.ContextSelection(SearchTool.Expansion.NOT_RELEVANT);
        });
        try (var tool = tool(8000)) {
            assertTrue(tool.searchKnowledge(List.of("PROJECT X fee"), null).startsWith("No relevant evidence"));
            assertTrue(events.stream().noneMatch(e -> e.source() != null));
        }
    }

    @Test
    void fullDocumentClassificationFetchesOnlyTheWiderBoundedWindow() {
        candidates();
        when(search.expand(any(), eq(section), eq(2))).thenReturn(List.of(new SearchPage.Passage(1, "Neighbor", "[]"), section.passages().getFirst(), section.passages().getLast()));
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of(1)));
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenReturn(
                new SearchTool.ContextSelection(SearchTool.Expansion.FULL_DOCUMENT));
        when(search.expand(any(), eq(section), eq(5))).thenReturn(java.util.stream.IntStream.range(0, 8).mapToObj(i -> new SearchPage.Passage(i, "Context " + i, "[]")).toList());
        try (var tool = tool(8000)) {
            String answer = tool.searchKnowledge(List.of("policy"), null);
            assertTrue(answer.contains("Context 7"));
            var ordered = inOrder(search);
            ordered.verify(search).scope(any());
            ordered.verify(search).ranked(any(SourceSearchScope.class), any(), any(), any());
            ordered.verify(search).expand(any(), eq(section), eq(2));
            ordered.verify(search).expand(any(), eq(section), eq(5));
            verifyNoMoreInteractions(search);
        }
    }

    @Test
    void stopDuringQueryRewriteCancelsThePhaseAndPreventsRetrieval() {
        when(runner.createObject(anyList(), eq(SearchTool.SemanticQuery.class))).thenAnswer(ignored -> {
            stopped.set(true);
            throw new IllegalStateException("native request interrupted");
        });
        try (var tool = tool(8000)) {
            assertThrows(CancellationException.class, () -> tool.searchKnowledge(List.of("policy"), null));
            verify(search, never()).ranked(any(SourceSearchScope.class), any(), any(), any());
        }
    }

    @Test
    void followUpRewritesUseHistoryAndAreCachedWhileToolQueriesKeepTheirOwnWeight() {
        var result = mock(SearchResults.class);
        when(result.hits()).thenReturn(List.of());
        var searches = new java.util.concurrent.atomic.AtomicInteger();
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(call -> {
            boolean firstSearch = searches.getAndIncrement() == 0;
            List<io.memoryos.retrieval.SearchQuery> queries = call.getArgument(1);
            assertEquals(firstSearch, queries.stream().anyMatch(q -> q.text().equals("AX-7 onboarding") && q.weight() == 1.3 && !q.keyword()));
            assertEquals(firstSearch, queries.stream().anyMatch(q -> q.text().equals("AX-7") && q.weight() == 1.0 && q.keyword()));
            assertTrue(queries.stream().anyMatch(q -> q.text().equals("How do I set it up?") && q.weight() == .5));
            assertTrue(queries.stream().anyMatch(q -> q.text().equals("setup instructions") && q.weight() == .7));
            return result;
        });
        when(runner.createObject(anyList(), eq(SearchTool.SemanticQuery.class))).thenAnswer(call -> {
            assertTrue(call.getArgument(0).toString().contains("AX-7"));
            return new SearchTool.SemanticQuery("AX-7 onboarding");
        });
        when(runner.createObject(anyList(), eq(SearchTool.KeywordQueries.class))).thenReturn(new SearchTool.KeywordQueries(List.of("AX-7")));
        try (var tool = new SearchTool(search, new ActorId(UUID.randomUUID()), runner, new JTokkitTokenCountEstimator(),
                new ChatSearchProperties(30, 10, 6000, 8000, 3, Duration.ofSeconds(5), false, Duration.ofSeconds(1)), () -> {}, () -> 8000, events::add, Mono.never(),
                List.of(new UserMessage("Tell me about AX-7"), new AssistantMessage("AX-7 is our internal system."),
                        new UserMessage("How do I set it up?")), Instant.now().plusSeconds(60), new io.memoryos.retrieval.SearchTimings(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), io.micrometer.observation.ObservationRegistry.NOOP))) {
            tool.beforeToolCall(new BeforeToolCallContext(new ToolCall("follow-up", "searchKnowledge", "{}")));
            tool.searchKnowledge(List.of("setup instructions"), null);
            tool.searchKnowledge(List.of("setup instructions"), null);
            verify(runner).createObject(anyList(), eq(SearchTool.SemanticQuery.class));
            verify(runner).createObject(anyList(), eq(SearchTool.KeywordQueries.class));
        }
    }

    @Test
    void duplicateWeightsSumBeforeRetrievalAndCachedExpansionIsOmittedFromLaterSearch() {
        var result = candidates();
        var batches = new ArrayList<List<io.memoryos.retrieval.SearchQuery>>();
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(call -> {
            batches.add(List.copyOf(call.getArgument(1))); return result;
        });
        when(runner.createObject(anyList(), eq(SearchTool.KeywordQueries.class))).thenReturn(
                new SearchTool.KeywordQueries(List.of("policy", "POLICY", "policy")));
        try (var tool = tool(8000)) {
            tool.searchKnowledge(List.of("policy"), null);
            tool.searchKnowledge(List.of("policy"), null);
            assertEquals(List.of(new io.memoryos.retrieval.SearchQuery("policy", false, 2.5),
                    new io.memoryos.retrieval.SearchQuery("policy", true, 3)), batches.getFirst());
            assertEquals(List.of(new io.memoryos.retrieval.SearchQuery("policy", false, 1.2)), batches.get(1));
        }
    }

    @Test
    void rewritesOverlapAndEachReceivesIndependentNonReasoningOptions() {
        candidates();
        var entered = new java.util.concurrent.CountDownLatch(2);
        when(runner.withLlm(any())).thenAnswer(call -> {
            LlmOptions options = call.getArgument(0);
            assertNotNull(options.getThinking()); assertFalse(options.getThinking().getEnabled());
            assertNotNull(options.getTimeout());
            assertTrue(options.getTimeout().compareTo(Duration.ofSeconds(5)) <= 0);
            return runner;
        });
        when(runner.createObject(anyList(), eq(SearchTool.SemanticQuery.class))).thenAnswer(_ -> {
            entered.countDown(); assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return new SearchTool.SemanticQuery("policy");
        });
        when(runner.createObject(anyList(), eq(SearchTool.KeywordQueries.class))).thenAnswer(_ -> {
            entered.countDown(); assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return new SearchTool.KeywordQueries(List.of("policy"));
        });
        try (var tool = tool(8000)) { assertTrue(tool.searchKnowledge(List.of("policy"), null).contains("[1] Policy")); }
    }

    @Test
    void selectionTimeoutCancelsTheHelperAndPublishesFallbackEvidence() {
        candidates();
        var interrupted = new AtomicBoolean();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenAnswer(_ -> {
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException expected) {
                interrupted.set(true); Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted");
            }
            throw new AssertionError("Selection should be interrupted");
        });
        try (var tool = tool(8000, Duration.ofMillis(200), false)) {
            assertTrue(tool.searchKnowledge(List.of("policy"), null).contains("[1] Policy"));
        }
        assertTrue(interrupted.get());
    }

    @Test
    void closeInterruptsAndDrainsRunningRetrievalBeforeReturning() throws Exception {
        candidates();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var drained = new AtomicBoolean();
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(_ -> {
            entered.countDown();
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            finally { drained.set(true); }
            throw new AssertionError("Retrieval must be interrupted");
        });
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (var tool = tool(8000)) {
            var invocation = Thread.ofVirtual().start(() -> {
                try { tool.searchKnowledge(List.of("policy"), null); }
                catch (Throwable expected) { failure.set(expected); }
            });
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            tool.close();
            assertTrue(drained.get());
            assertTrue(invocation.join(Duration.ofSeconds(3)));
            assertNotNull(failure.get());
            assertThrows(CancellationException.class, () -> tool.searchKnowledge(List.of("policy"), null));
            assertTrue(events.stream().noneMatch(e -> e.source() != null));
        }
    }

    @Test
    void longTitleIsBoundedOnlyInReadingProgressAndPreservesFullCitationTitle() {
        var result = candidates();
        String title = "T".repeat(254) + "😀 full document title";
        var hit = new SearchHit(document, generation, 2, title, "text/plain", "Evidence", "[]", Instant.EPOCH, .9);
        when(result.hits()).thenReturn(List.of(hit));
        when(result.sections()).thenReturn(List.of(new SearchSection(hit, List.of(hit))));
        try (var tool = tool(8000)) {
            assertTrue(tool.searchKnowledge(List.of("policy"), null).contains(title));
            var reading = events.stream().flatMap(e -> e.documents().stream()).findFirst().orElseThrow();
            assertEquals("T".repeat(254), reading.title());
            var source = events.stream().map(ChatSearchEvent::source).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            assertEquals(title, source.title());
        }
    }

    @Test
    void closeBoundsUncooperativeRetrievalAndRejectsItsLateEvidence() throws Exception {
        var result = candidates();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(_ -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; }
                catch (InterruptedException ignored) { /* Simulates uncooperative IO. */ }
            }
            return result;
        });
        try (var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(); var tool = tool(8000)) {
            var invocation = tasks.submit(() -> tool.searchKnowledge(List.of("policy"), null));
            try {
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
                tasks.submit(tool::close).get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(tool.whenDrained().isDone());
                assertThrows(CancellationException.class, () -> tool.searchKnowledge(List.of("policy"), null));
            } finally { release.countDown(); }
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> invocation.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            tool.whenDrained().get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(events.stream().noneMatch(e -> e.source() != null || !e.documents().isEmpty()));
        }
    }

    @Test
    void longSectionUsesThreeChunksAroundAnchorForSelectionAndRetainsFullEvidence() {
        var result = candidates();
        var chunks = java.util.stream.IntStream.range(0, 20).mapToObj(this::hit).toList();
        var longSection = new SearchSection(chunks.get(10), chunks);
        when(result.hits()).thenReturn(chunks);
        when(result.sections()).thenReturn(List.of(longSection));
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            assertTrue(prompt.contains("Section 9\nSection 10\nSection 11"));
            assertFalse(prompt.contains("Section 12")); assertFalse(prompt.contains("Section 8\n"));
            return new SearchTool.Selection(List.of(1));
        });
        try (var tool = tool(8000)) { assertTrue(tool.searchKnowledge(List.of("policy"), null).contains("Section 19")); }
    }

    @Test
    void sourceSwitchReusesExpansionOnlyForPreviouslyUnsearchedTypeAndTimeRunsOnce() {
        candidates();
        when(search.scope(any())).thenReturn(new SourceSearchScope(scope.tenant(), Map.of(UUID.randomUUID(), SourceType.FILE,
                UUID.randomUUID(), SourceType.GOOGLE_DRIVE)));
        when(runner.createObject(anyString(), eq(SearchTool.SourceChoice.class))).thenReturn(
                new SearchTool.SourceChoice(List.of(SourceType.FILE), true), new SearchTool.SourceChoice(List.of(SourceType.GOOGLE_DRIVE), true),
                new SearchTool.SourceChoice(List.of(SourceType.FILE), true));
        when(runner.createObject(anyString(), eq(SearchTool.TimeChoice.class))).thenReturn(new SearchTool.TimeChoice(null, null, null, null));
        try (var tool = tool(8000, Duration.ofSeconds(5), true)) {
            for (int i = 0; i < 3; i++) tool.searchKnowledge(List.of("new query"), null);
            var plans = events.stream().map(ChatSearchEvent::search).filter(java.util.Objects::nonNull).toList();
            assertEquals(List.of("policy", "new query"), plans.get(0).queries());
            assertEquals(List.of("policy", "new query"), plans.get(1).queries());
            assertEquals(List.of("new query", "policy"), plans.get(2).queries());
            verify(runner).createObject(anyList(), eq(SearchTool.SemanticQuery.class));
            verify(runner).createObject(anyString(), eq(SearchTool.TimeChoice.class));
        }
    }

    @Test
    void filtersPreserveExplicitIntervalWhenInferenceConflictsAndSkipSingleSourceClassification() {
        candidates();
        var explicit = new io.memoryos.retrieval.SearchFilters(java.util.Set.of(SourceType.FILE), null,
                new io.memoryos.retrieval.SearchFilters.Interval(Instant.parse("2026-09-01T00:00:00Z"), null));
        when(runner.createObject(anyString(), eq(SearchTool.TimeChoice.class))).thenReturn(
                new SearchTool.TimeChoice(null, null, null, "2026-08-01T00:00:00Z"));
        try (var tool = tool(8000, Duration.ofSeconds(5), true)) {
            tool.searchKnowledge(List.of("policy"), explicit);
            assertEquals(explicit, events.stream().map(ChatSearchEvent::search).filter(java.util.Objects::nonNull).findFirst().orElseThrow().filters());
            verify(runner, never()).createObject(anyString(), eq(SearchTool.SourceChoice.class));
        }
    }

    @Test
    void selectedDocumentsPrecedeParallelClassificationAndCompletionOrderDoesNotChangeCitations() {
        var result = candidates();
        var later = new SearchSection(hit(8), List.of(hit(8)));
        when(result.hits()).thenReturn(List.of(first, second, hit(8)));
        when(result.sections()).thenReturn(List.of(section, later));
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of(2, 1)));
        var entered = new java.util.concurrent.CountDownLatch(2);
        var earlierFinished = new java.util.concurrent.CountDownLatch(1);
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenAnswer(call -> {
            var reading = events.stream().filter(e -> e.stage() == ChatSearchEvent.Stage.EXPANDING).findFirst().orElseThrow();
            assertEquals(2, reading.documents().size());
            entered.countDown(); assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            if (call.<String>getArgument(0).contains("Section 8")) assertTrue(earlierFinished.await(3, java.util.concurrent.TimeUnit.SECONDS));
            else earlierFinished.countDown();
            return new SearchTool.ContextSelection(SearchTool.Expansion.MAIN_SECTION_ONLY);
        });
        try (var tool = tool(8000)) {
            String evidence = tool.searchKnowledge(List.of("policy"), null);
            assertTrue(evidence.indexOf("Section 2") < evidence.indexOf("Section 8"));
            assertEquals(List.of(1, 2), events.stream().filter(e -> e.source() != null).map(e -> e.source().citationId()).toList());
        }
    }
}
