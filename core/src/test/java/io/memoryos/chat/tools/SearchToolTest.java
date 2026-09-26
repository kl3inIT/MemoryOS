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
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.retrieval.DocumentOriginalService;
import io.memoryos.retrieval.SearchDocumentUnavailableException;
import io.memoryos.retrieval.SearchQuery;
import io.memoryos.retrieval.SearchTimings;
import io.memoryos.shared.TenantId;
import io.memoryos.retrieval.SearchSection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import com.embabel.chat.UserMessage;
import com.embabel.chat.AssistantMessage;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.shared.ActorId;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchFilters;
import io.memoryos.retrieval.SearchResults;
import io.memoryos.retrieval.SearchHit;
import io.memoryos.retrieval.SearchPage;
import io.memoryos.retrieval.SearchUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import reactor.core.publisher.Mono;

class SearchToolTest {
    @Test
    void knowledgeCutoffIsALowerBoundThatRequestsCannotWiden() {
        var cutoff = Instant.parse("2026-01-01T00:00:00Z");
        var floor = new SearchFilters.Interval(cutoff, null);
        Assertions.assertEquals(floor, SearchTool.floor(null, floor));
        var earlier = new SearchFilters.Interval(Instant.parse("2025-01-01T00:00:00Z"), null);
        Assertions.assertEquals(cutoff, SearchTool.floor(earlier, floor).from());
        var before = new SearchFilters.Interval(null, Instant.parse("2025-06-01T00:00:00Z"));
        var empty = SearchTool.floor(before, floor);
        Assertions.assertEquals(empty.from(), empty.to());
        Assertions.assertTrue(SearchTool.beforeFloor(before, floor));
        Assertions.assertFalse(SearchTool.beforeFloor(earlier, floor));
        Assertions.assertNull(SearchTool.floor(null, null));
    }

    private final DocumentSearchService search = mock(DocumentSearchService.class);
    private final PromptRunner runner = mock(PromptRunner.class);
    private final List<ChatToolEvent> events = new ArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final UUID document = UUID.randomUUID();
    private final UUID generation = UUID.randomUUID();
    private final SearchHit first = hit(2);
    private final SearchHit second = hit(3);
    private final SearchSection section = new SearchSection(first, List.of(first, second));
    private final SourceSearchScope scope = new SourceSearchScope(new TenantId(UUID.randomUUID()), new ActorId(UUID.randomUUID()), Map.of(UUID.randomUUID(), SourceType.FILE));

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
        return tool(availableTokens, timeout, detectFilters, null);
    }

    /** A null allowlist is an agent that restricts nothing; an empty one restricts the turn to no Source at all. */
    private SearchTool tool(int availableTokens, Duration timeout, boolean detectFilters, @Nullable List<UUID> sourceIds) {
        var tool = new SearchTool(search, scope.actor(), runner, new JTokkitTokenCountEstimator(),
                new ChatSearchProperties(30, 10, 6000, timeout, detectFilters, Duration.ofSeconds(1)), () -> {
                    if (stopped.get()) throw new CancellationException();
                }, () -> availableTokens, events::add, Mono.never(), List.of(new UserMessage("policy")), new SearchTimings(new SimpleMeterRegistry(), ObservationRegistry.NOOP), sourceIds);
        tool.activity().beforeToolCall(new BeforeToolCallContext(new ToolCall("tool-1", "search_knowledge", "{}")));
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
        when(search.window(eq(result), any(SearchSection.class), anyInt())).thenAnswer(call -> call.<SearchSection>getArgument(1).passages());
        when(search.authorizedSections(eq(result), anyList())).thenAnswer(call -> call.getArgument(1));
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
            verify(search, times(2)).window(any(), any(SearchSection.class), eq(2));
        }
    }

    @Test
    void aHitWithAStoredOriginalIsStagedAndItsEvidenceSaysSoLikeOnyx() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of(1)));
        var originals = mock(DocumentOriginalService.class);
        var stored = new StoredObjectId(UUID.randomUUID());
        when(originals.citationOriginals(eq(scope.actor()), any())).thenReturn(Map.of(document,
                new StoredObjectReference(stored, new ObjectKey("raw/policy"),
                        "policy.xlsx", new ObjectMetadata(42, "application/vnd.ms-excel",
                        new ContentSha256("a".repeat(64))))));
        var sandbox = new SandboxDocuments(originals, scope.actor());
        try (var tool = tool(8000).withSandbox(sandbox)) {
            var response = tool.searchKnowledge(List.of("policy"), null);
            String name = "Policy_" + stored.value() + ".xlsx";
            assertTrue(response.contains("[1] Policy\nOnly a short excerpt from this document is shown below. The complete file "
                    + "is available in the sandbox as \"" + name + "\" — prefer the Python code interpreter to read, parse, or "
                    + "analyze it\n\nExcerpt: Section 2\nSection 3"), response);
            var staged = sandbox.documents();
            assertEquals(1, staged.size());
            assertEquals(new SandboxDocuments.Document(document, generation, name, 42, "a".repeat(64), "application/vnd.ms-excel"),
                    staged.getFirst());
        }
    }

    @Test
    void emptySelectionFallsBackToRankedEvidenceInsteadOfReportingNoDocuments() {
        // A query that only names a document matches its title, so the selector may see no relevant passage.
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of()));
        try (var tool = tool(8000)) {
            var response = tool.searchKnowledge(List.of("Policy.pdf"), null);
            assertTrue(response.contains("[1] Policy\nSection 2\nSection 3"));
            assertEquals(1, events.stream().filter(e -> e.source() != null).count());
            verify(runner).createObject(anyString(), eq(SearchTool.ContextSelection.class));
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
            verify(search, never()).window(any(), any(SearchSection.class), anyInt());
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
        when(search.window(any(), eq(section), eq(2))).thenReturn(IntStream.range(0, 5).mapToObj(i -> new SearchPage.Passage(i,
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
            verify(search, never()).window(any(), any(SearchSection.class), anyInt());
            assertTrue(events.stream().noneMatch(e -> e.source() != null));
        }
    }

    @Test
    void nativeToolSchemaAndBindingUseNamedParametersAndRejectUnboundedArguments() {
        try (var tool = tool(8000)) {
            var nativeTool = Tool.fromInstance(tool).getFirst();
            assertEquals("search_knowledge", nativeTool.getDefinition().getName());
            var result = nativeTool.call("{\"queries\":[\"a\",\"b\",\"c\",\"d\"]}");
            assertTrue(assertInstanceOf(Tool.Result.Text.class, result).getContent().contains("Invalid search arguments"));
            verifyNoInteractions(search);
        }
    }

    @Test
    void classificationReadsNeighborsAndKeepsTheMainSectionWhenNotRelevant() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of(1)));
        when(search.window(any(), eq(section), eq(2))).thenReturn(List.of(new SearchPage.Passage(1, "This contract is for PROJECT Y, not PROJECT X.", "[]"),
                        new SearchPage.Passage(2, "The quoted fee is 100000.", "[]")));
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenAnswer(call -> {
            assertTrue(call.<String>getArgument(0).contains("PROJECT Y"));
            verify(search).window(any(), eq(section), eq(2));
            return new SearchTool.ContextSelection(SearchTool.Expansion.NOT_RELEVANT);
        });
        try (var tool = tool(8000)) {
            // As in the reference, a NOT_RELEVANT classification keeps the selected main section instead of dropping it.
            String response = tool.searchKnowledge(List.of("PROJECT X fee"), null);
            assertTrue(response.contains("[1] Policy\nSection 2\nSection 3"));
            assertFalse(response.contains("PROJECT Y"));
        }
    }

    @Test
    void fullDocumentClassificationFetchesOnlyTheWiderBoundedWindow() {
        candidates();
        when(search.window(any(), eq(section), eq(2))).thenReturn(List.of(new SearchPage.Passage(1, "Neighbor", "[]"), section.passages().getFirst(), section.passages().getLast()));
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of(1)));
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenReturn(
                new SearchTool.ContextSelection(SearchTool.Expansion.FULL_DOCUMENT));
        when(search.window(any(), eq(section), eq(5))).thenReturn(IntStream.range(0, 8).mapToObj(i -> new SearchPage.Passage(i, "Context " + i, "[]")).toList());
        try (var tool = tool(8000)) {
            String answer = tool.searchKnowledge(List.of("policy"), null);
            assertTrue(answer.contains("Context 7"));
            // One recheck after selection and one before returning evidence, not one per window read.
            verify(search, times(2)).authorizedSections(any(), anyList());
        }
    }

    @Test
    void documentRemovedDuringWindowReadYieldsNoEvidenceInsteadOfFailingTheTurn() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of(1)));
        when(search.window(any(), eq(section), eq(2))).thenThrow(new SearchDocumentUnavailableException());
        try (var tool = tool(8000)) {
            assertTrue(tool.searchKnowledge(List.of("policy"), null).startsWith("No relevant evidence"));
            assertTrue(events.stream().noneMatch(event -> event.source() != null));
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
        var searches = new AtomicInteger();
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(call -> {
            boolean firstSearch = searches.getAndIncrement() == 0;
            List<SearchQuery> queries = call.getArgument(1);
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
                new ChatSearchProperties(30, 10, 6000, Duration.ofSeconds(5), false, Duration.ofSeconds(1)), () -> {}, () -> 8000, events::add, Mono.never(),
                List.of(new UserMessage("Tell me about AX-7"), new AssistantMessage("AX-7 is our internal system."),
                        new UserMessage("How do I set it up?")), new SearchTimings(new SimpleMeterRegistry(), ObservationRegistry.NOOP))) {
            tool.activity().beforeToolCall(new BeforeToolCallContext(new ToolCall("follow-up", "search_knowledge", "{}")));
            tool.searchKnowledge(List.of("setup instructions"), null);
            tool.searchKnowledge(List.of("setup instructions"), null);
            verify(runner).createObject(anyList(), eq(SearchTool.SemanticQuery.class));
            verify(runner).createObject(anyList(), eq(SearchTool.KeywordQueries.class));
        }
    }

    @Test
    void anInferredDateWindowThatMatchesNothingIsDroppedAndTheSearchIsRepeated() {
        // Google Drive items carry no source dates, so an inferred window removed the whole corpus.
        when(runner.createObject(anyString(), eq(SearchTool.TimeChoice.class))).thenReturn(
                new SearchTool.TimeChoice("updated", "2025-09-01", "2025-09-30"));
        when(runner.createObject(anyString(), eq(SearchTool.SourceChoice.class)))
                .thenReturn(new SearchTool.SourceChoice(List.of()));
        var observed = new ArrayList<SearchFilters>();
        var empty = mock(SearchResults.class);
        when(empty.hits()).thenReturn(List.of());
        var found = candidates();
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(call -> {
            SearchFilters filters = call.getArgument(2);
            observed.add(filters);
            return filters.updated() == null ? found : empty;
        });
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class)))
                .thenReturn(new SearchTool.Selection(List.of(1)));

        String evidence;
        try (var tool = tool(8000, Duration.ofSeconds(5), true)) {
            evidence = tool.searchKnowledge(List.of("policy"), null);
        }

        assertEquals(2, observed.size(), "the same queries are asked again without the inferred window");
        assertTrue(observed.getFirst().updated() != null);
        assertTrue(observed.getLast().updated() == null);
        assertTrue(evidence.contains("Section"), "the retry returns the evidence the window had hidden");
    }

    @Test
    void aTurnThatStillFindsNothingAfterDroppingTheWindowSaysSo() {
        // Otherwise the model reports an empty knowledge base when what was empty was the period.
        when(runner.createObject(anyString(), eq(SearchTool.TimeChoice.class))).thenReturn(
                new SearchTool.TimeChoice("updated", "2025-09-01", "2025-09-30"));
        when(runner.createObject(anyString(), eq(SearchTool.SourceChoice.class)))
                .thenReturn(new SearchTool.SourceChoice(List.of()));
        var empty = mock(SearchResults.class);
        when(empty.hits()).thenReturn(List.of());
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenReturn(empty);

        String evidence;
        try (var tool = tool(8000, Duration.ofSeconds(5), true)) {
            evidence = tool.searchKnowledge(List.of("policy"), null);
        }

        verify(search, times(2)).ranked(any(SourceSearchScope.class), any(), any(), any());
        assertTrue(evidence.contains("dropped"), "the dropped window is reported even with no evidence");
    }

    @Test
    void anInferenceTheExplicitWindowAlreadyCoversIsNotSearchedTwice() {
        // The intersection is the explicit window, so there is nothing inferred left to drop.
        when(runner.createObject(anyString(), eq(SearchTool.TimeChoice.class))).thenReturn(
                new SearchTool.TimeChoice("updated", "2025-09-01", "2025-09-30"));
        when(runner.createObject(anyString(), eq(SearchTool.SourceChoice.class)))
                .thenReturn(new SearchTool.SourceChoice(List.of()));
        var empty = mock(SearchResults.class);
        when(empty.hits()).thenReturn(List.of());
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenReturn(empty);
        var september = new SearchFilters(Set.of(), null, new SearchFilters.Interval(
                Instant.parse("2025-09-01T00:00:00Z"), Instant.parse("2025-09-30T23:59:59Z")));

        String evidence;
        try (var tool = tool(8000, Duration.ofSeconds(5), true)) {
            evidence = tool.searchKnowledge(List.of("policy"), september);
        }

        verify(search).ranked(any(SourceSearchScope.class), any(), any(), any());
        assertFalse(evidence.contains("dropped"));
    }

    @Test
    void aDateWindowTheUserAskedForIsNotDroppedWhenItMatchesNothing() {
        // Only the helper's own guess is retried away: an empty result is the honest answer to an explicit window.
        when(runner.createObject(anyString(), eq(SearchTool.TimeChoice.class)))
                .thenReturn(new SearchTool.TimeChoice(null, null, null));
        when(runner.createObject(anyString(), eq(SearchTool.SourceChoice.class)))
                .thenReturn(new SearchTool.SourceChoice(List.of()));
        var observed = new ArrayList<SearchFilters>();
        var empty = mock(SearchResults.class);
        when(empty.hits()).thenReturn(List.of());
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(call -> {
            observed.add(call.getArgument(2));
            return empty;
        });
        var september = new SearchFilters(Set.of(), null,
                new SearchFilters.Interval(Instant.parse("2025-09-01T00:00:00Z"), Instant.parse("2025-09-30T23:59:59Z")));

        String evidence;
        try (var tool = tool(8000, Duration.ofSeconds(5), true)) {
            evidence = tool.searchKnowledge(List.of("policy"), september);
        }

        assertEquals(1, observed.size(), "an explicit window is asked once and kept");
        assertEquals(september.updated(), observed.getFirst().updated());
        assertFalse(evidence.contains("dropped"), "nothing is said about dropping a window the user asked for");
    }

    @Test
    void anAgentWhoseAttachedSourcesAllResolveToNothingSearchesNothing() {
        var observed = new ArrayList<SourceSearchScope>();
        var empty = mock(SearchResults.class);
        when(empty.hits()).thenReturn(List.of());
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(call -> {
            observed.add(call.getArgument(0)); return empty;
        });
        try (var tool = tool(8000, Duration.ofSeconds(5), false, List.of())) {
            tool.searchKnowledge(List.of("policy"), null);
        }
        assertEquals(1, observed.size());
        assertTrue(observed.getFirst().sources().isEmpty(),
                "An agent attached only to Document Sets this actor cannot use must not fall back to every readable Source");
    }

    @Test
    void personaSourceSelectionRechecksRevocationWithoutWideningToOtherReadableSources() {
        UUID selected = UUID.randomUUID(), unselected = UUID.randomUUID();
        when(search.scope(any())).thenReturn(
                new SourceSearchScope(scope.tenant(), scope.actor(), Map.of(selected, SourceType.FILE, unselected, SourceType.FILE)),
                new SourceSearchScope(scope.tenant(), scope.actor(), Map.of(unselected, SourceType.FILE)));
        var observed = new ArrayList<SourceSearchScope>();
        var empty = mock(SearchResults.class);
        when(empty.hits()).thenReturn(List.of());
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(call -> {
            observed.add(call.getArgument(0)); return empty;
        });
        try (var tool = tool(8000, Duration.ofSeconds(5), false, List.of(selected))) {
            tool.searchKnowledge(List.of("policy"), null);
            tool.searchKnowledge(List.of("follow up"), null);
        }
        assertEquals(2, observed.size());
        assertEquals(Map.of(selected, SourceType.FILE), observed.getFirst().sources());
        assertTrue(observed.getLast().sources().isEmpty(), "Revoking the only selected source must not expose other readable sources");
        assertTrue(events.stream().noneMatch(event -> event.source() != null));
    }
    @Test
    void groupRevocationDuringContextClassificationDoesNotReturnEarlierEvidence() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of(1)));
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenAnswer(_ -> {
            when(search.authorizedSections(any(), anyList())).thenReturn(List.of());
            return new SearchTool.ContextSelection(SearchTool.Expansion.MAIN_SECTION_ONLY);
        });
        try (var tool = tool(8000)) {
            var answer = tool.searchKnowledge(List.of("policy"), null);
            assertFalse(answer.contains("Section 2"));
            assertTrue(events.stream().noneMatch(event -> event.source() != null));
        }
    }

    @Test
    void groupRevocationDuringSelectionDoesNotStreamPrivateDocumentMetadata() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenAnswer(_ -> {
            when(search.authorizedSections(any(), anyList())).thenReturn(List.of());
            return new SearchTool.Selection(List.of(1));
        });
        try (var tool = tool(8000)) {
            String answer = tool.searchKnowledge(List.of("policy"), null);
            assertFalse(answer.contains(first.content()));
            assertTrue(events.stream().noneMatch(event -> event.source() != null));
            assertTrue(events.stream().flatMap(event -> event.documents().stream())
                    .noneMatch(reading -> reading.documentId().equals(document)));
        }
    }


    @Test
    void duplicateWeightsSumBeforeRetrievalAndCachedExpansionIsOmittedFromLaterSearch() {
        var result = candidates();
        var batches = new ArrayList<List<SearchQuery>>();
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(call -> {
            batches.add(List.copyOf(call.getArgument(1))); return result;
        });
        when(runner.createObject(anyList(), eq(SearchTool.KeywordQueries.class))).thenReturn(
                new SearchTool.KeywordQueries(List.of("policy", "POLICY", "policy")));
        try (var tool = tool(8000)) {
            tool.searchKnowledge(List.of("policy"), null);
            tool.searchKnowledge(List.of("policy"), null);
            assertEquals(List.of(new SearchQuery("policy", false, 2.5),
                    new SearchQuery("policy", true, 3)), batches.getFirst());
            assertEquals(List.of(new SearchQuery("policy", false, 1.2)), batches.get(1));
        }
    }

    @Test
    void rewritesOverlapAndEachReceivesIndependentNonReasoningOptions() {
        candidates();
        var entered = new CountDownLatch(2);
        when(runner.withLlm(any())).thenAnswer(call -> {
            LlmOptions options = call.getArgument(0);
            assertNotNull(options.getThinking()); assertFalse(options.getThinking().getEnabled());
            assertNotNull(options.getTimeout());
            assertTrue(options.getTimeout().compareTo(Duration.ofSeconds(5)) <= 0);
            return runner;
        });
        when(runner.createObject(anyList(), eq(SearchTool.SemanticQuery.class))).thenAnswer(_ -> {
            entered.countDown(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            return new SearchTool.SemanticQuery("policy");
        });
        when(runner.createObject(anyList(), eq(SearchTool.KeywordQueries.class))).thenAnswer(_ -> {
            entered.countDown(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            return new SearchTool.KeywordQueries(List.of("policy"));
        });
        try (var tool = tool(8000)) { assertTrue(tool.searchKnowledge(List.of("policy"), null).contains("[1] Policy")); }
    }

    @Test
    void selectionTimeoutCancelsTheHelperAndPublishesFallbackEvidence() {
        candidates();
        var interrupted = new AtomicBoolean();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenAnswer(_ -> {
            try { new CountDownLatch(1).await(); }
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
        var entered = new CountDownLatch(1);
        var drained = new AtomicBoolean();
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(_ -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            finally { drained.set(true); }
            throw new AssertionError("Retrieval must be interrupted");
        });
        var failure = new AtomicReference<Throwable>();
        try (var tool = tool(8000)) {
            var invocation = Thread.ofVirtual().start(() -> {
                try { tool.searchKnowledge(List.of("policy"), null); }
                catch (Throwable expected) { failure.set(expected); }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
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
            var source = events.stream().map(ChatToolEvent::source).filter(Objects::nonNull).findFirst().orElseThrow();
            assertEquals(title, source.title());
        }
    }

    @Test
    void closeBoundsUncooperativeRetrievalAndRejectsItsLateEvidence() throws Exception {
        var result = candidates();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(search.ranked(any(SourceSearchScope.class), any(), any(), any())).thenAnswer(_ -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; }
                catch (InterruptedException ignored) { /* Simulates uncooperative IO. */ }
            }
            return result;
        });
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor(); var tool = tool(8000)) {
            var invocation = tasks.submit(() -> tool.searchKnowledge(List.of("policy"), null));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                tasks.submit(tool::close).get(2, TimeUnit.SECONDS);
                assertFalse(tool.whenDrained().isDone());
                assertThrows(CancellationException.class, () -> tool.searchKnowledge(List.of("policy"), null));
            } finally { release.countDown(); }
            var failure = assertThrows(ExecutionException.class,
                    () -> invocation.get(3, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            tool.whenDrained().get(3, TimeUnit.SECONDS);
            assertTrue(events.stream().noneMatch(e -> e.source() != null || !e.documents().isEmpty()));
        }
    }

    @Test
    void longSectionUsesThreeChunksAroundAnchorForSelectionAndRetainsFullEvidence() {
        var result = candidates();
        var chunks = IntStream.range(0, 20).mapToObj(this::hit).toList();
        var longSection = new SearchSection(chunks.get(10), chunks);
        when(result.hits()).thenReturn(chunks);
        when(result.sections()).thenReturn(List.of(longSection));
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            assertTrue(prompt.contains("Section 9\\nSection 10\\nSection 11"));
            assertFalse(prompt.contains("Section 12")); assertFalse(prompt.contains("Section 8\\n"));
            return new SearchTool.Selection(List.of(1));
        });
        try (var tool = tool(8000)) { assertTrue(tool.searchKnowledge(List.of("policy"), null).contains("Section 19")); }
    }

    @Test
    void sourceSwitchReusesExpansionOnlyForPreviouslyUnsearchedTypeAndTimeRunsOnce() {
        candidates();
        when(search.scope(any())).thenReturn(new SourceSearchScope(scope.tenant(), scope.actor(), Map.of(UUID.randomUUID(), SourceType.FILE,
                UUID.randomUUID(), SourceType.GOOGLE_DRIVE)));
        when(runner.createObject(anyString(), eq(SearchTool.SourceChoice.class))).thenReturn(
                new SearchTool.SourceChoice(List.of(SourceType.FILE)), new SearchTool.SourceChoice(List.of(SourceType.GOOGLE_DRIVE)),
                new SearchTool.SourceChoice(List.of(SourceType.FILE)));
        when(runner.createObject(anyString(), eq(SearchTool.TimeChoice.class))).thenReturn(new SearchTool.TimeChoice(null, null, null));
        try (var tool = tool(8000, Duration.ofSeconds(5), true)) {
            for (int i = 0; i < 3; i++) tool.searchKnowledge(List.of("new query"), null);
            var plans = events.stream().map(ChatToolEvent::search).filter(Objects::nonNull).toList();
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
        var explicit = new SearchFilters(Set.of(SourceType.FILE), null,
                new SearchFilters.Interval(Instant.parse("2026-09-01T00:00:00Z"), null));
        when(runner.createObject(anyString(), eq(SearchTool.TimeChoice.class))).thenReturn(
                new SearchTool.TimeChoice("updated", null, "2026-08-01"));
        try (var tool = tool(8000, Duration.ofSeconds(5), true)) {
            tool.searchKnowledge(List.of("policy"), explicit);
            assertEquals(explicit, events.stream().map(ChatToolEvent::search).filter(Objects::nonNull).findFirst().orElseThrow().filters());
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
        var entered = new CountDownLatch(2);
        var earlierFinished = new CountDownLatch(1);
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenAnswer(call -> {
            var reading = events.stream().filter(e -> e.stage() == ChatToolEvent.Stage.EXPANDING).findFirst().orElseThrow();
            assertEquals(2, reading.documents().size());
            entered.countDown(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            if (call.<String>getArgument(0).contains("Section 8")) assertTrue(earlierFinished.await(3, TimeUnit.SECONDS));
            else earlierFinished.countDown();
            return new SearchTool.ContextSelection(SearchTool.Expansion.MAIN_SECTION_ONLY);
        });
        try (var tool = tool(8000)) {
            String evidence = tool.searchKnowledge(List.of("policy"), null);
            assertTrue(evidence.indexOf("Section 2") < evidence.indexOf("Section 8"));
            assertEquals(List.of(1, 2), events.stream().filter(e -> e.source() != null).map(e -> e.source().citationId()).toList());
        }
    }

    @Test
    void timeDecisionResolvesRelativeOffsetsDropsFutureBoundsAndDefaultsToUpdated() {
        var now = ZonedDateTime.parse("2026-09-14T10:00:00Z");
        assertEquals(new SearchFilters(Set.of(), null,
                        new SearchFilters.Interval(Instant.parse("2026-08-31T10:00:00Z"), null)),
                SearchTool.timeFilter(new SearchTool.TimeChoice(null, "-P2W", "None"), now));
        assertEquals(new SearchFilters(Set.of(), new SearchFilters.Interval(
                        Instant.parse("2022-01-01T00:00:00Z"), Instant.parse("2022-12-31T23:59:59.999999999Z")), null),
                SearchTool.timeFilter(new SearchTool.TimeChoice("created", "2022-01-01", "2022-12-31"), now));
        assertEquals(SearchFilters.NONE,
                SearchTool.timeFilter(new SearchTool.TimeChoice("updated", "2026-10-01", "2026-09-20"), now));
        assertEquals(SearchFilters.NONE,
                SearchTool.timeFilter(new SearchTool.TimeChoice("updated", "2026-9", "later"), now));
    }

    @Test
    void sourceScopeDecisionLatchesOffOnceNoSourceIsNamed() {
        candidates();
        when(search.scope(any())).thenReturn(new SourceSearchScope(scope.tenant(), scope.actor(), Map.of(UUID.randomUUID(), SourceType.FILE,
                UUID.randomUUID(), SourceType.GOOGLE_DRIVE)));
        when(runner.createObject(anyString(), eq(SearchTool.SourceChoice.class))).thenReturn(new SearchTool.SourceChoice(List.of()));
        when(runner.createObject(anyString(), eq(SearchTool.TimeChoice.class))).thenReturn(new SearchTool.TimeChoice(null, null, null));
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of(1)));
        try (var tool = tool(8000, Duration.ofSeconds(5), true)) {
            assertFalse(tool.searchKnowledge(List.of("policy"), null).contains("This internal search covered only"));
            tool.searchKnowledge(List.of("policy again"), null);
            verify(runner).createObject(anyString(), eq(SearchTool.SourceChoice.class));
        }
    }
}
