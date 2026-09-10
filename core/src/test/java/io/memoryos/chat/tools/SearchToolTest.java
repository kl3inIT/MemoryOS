package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.BeforeToolCallContext;
import com.embabel.chat.ToolCall;
import com.embabel.chat.UserMessage;
import com.embabel.chat.AssistantMessage;
import io.memoryos.chat.ChatSearchEvent;
import io.memoryos.iam.ActorId;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchResults;
import io.memoryos.retrieval.SearchHit;
import io.memoryos.retrieval.SearchDocument;
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

    @BeforeEach
    void rewrites() {
        when(runner.createObject(anyList(), eq(SearchTool.SemanticQuery.class))).thenReturn(new SearchTool.SemanticQuery("policy"));
        when(runner.createObject(anyList(), eq(SearchTool.KeywordQueries.class))).thenReturn(new SearchTool.KeywordQueries(List.of("policy")));
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenReturn(
                new SearchTool.ContextSelection(SearchTool.Expansion.MAIN_SECTION_ONLY));
    }

    private SearchTool tool(int availableTokens) {
        var tool = new SearchTool(search, new ActorId(UUID.randomUUID()), runner, new JTokkitTokenCountEstimator(),
                new ChatSearchProperties(12, 5, 6000, 8000, 3), () -> {
                    if (stopped.get()) throw new CancellationException();
                }, () -> availableTokens, events::add, Mono.never(), List.of(new UserMessage("policy")));
        tool.beforeToolCall(new BeforeToolCallContext(new ToolCall("tool-1", "searchKnowledge", "{}")));
        return tool;
    }

    private SearchHit hit(int ordinal) {
        return new SearchHit(document, generation, ordinal, "Policy", "text/plain", "Section " + ordinal, "[]", Instant.EPOCH, .9);
    }

    private void candidates() {
        var result = mock(SearchResults.class);
        when(result.hits()).thenReturn(List.of(first, second));
        when(search.ranked(any(), any(), any())).thenReturn(result);
        when(search.expand(eq(result), any(), anyInt())).thenAnswer(call -> {
            SearchHit hit = call.getArgument(1);
            return new SearchDocument(document, generation, "Policy", List.of(
                    new SearchPage.Passage(hit.ordinal(), hit.content(), "[]")), hit.ordinal(), 4, false);
        });
    }

    @Test
    void invalidSelectionCannotIntroduceDocumentIdsAndFallsBackToMergedRankedEvidence() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(
                new SearchTool.Selection(List.of(999)));
        try (var tool = tool(8000)) {
            var response = tool.searchKnowledge(List.of("policy"));
            assertTrue(response.contains("[1] Policy\nSection 2\nSection 3"));
            assertFalse(response.contains("999"));
            var sources = events.stream().filter(e -> e.source() != null).toList();
            assertEquals(1, sources.size());
            var source = sources.getFirst().source();
            assertNotNull(source);
            assertEquals(2, source.startOrdinal());
            assertEquals(3, source.endOrdinal());
            assertEquals(response, tool.searchKnowledge(List.of("policy")));
            assertEquals(1, events.stream().filter(e -> e.source() != null).count());
            verify(search, times(4)).expand(any(), any(), eq(2));
        }
    }

    @Test
    void noEvidenceSkipsSelectionAndUnavailableSearchIsDistinctFromNoMatches() {
        var result = mock(SearchResults.class);
        when(result.hits()).thenReturn(List.of());
        when(search.ranked(any(), any(), any())).thenReturn(result).thenThrow(new SearchUnavailableException());
        try (var tool = tool(8000)) {
            assertTrue(tool.searchKnowledge(List.of("policy")).startsWith("No authorized evidence"));
            assertTrue(tool.searchKnowledge(List.of("policy")).contains("unavailable"));
            verify(runner, never()).createObject(anyString(), eq(SearchTool.Selection.class));
            verify(search, never()).expand(any(), any(), anyInt());
        }
    }

    @Test
    void contextBoundDoesNotEmitSourcesThatWereNotIncludedInTheToolResult() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(
                new SearchTool.Selection(List.of(1)));
        try (var tool = tool(1)) {
            assertEquals("No evidence fits the available context.", tool.searchKnowledge(List.of("policy")));
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
        when(search.expand(any(), eq(first), eq(2))).thenReturn(new SearchDocument(document, generation, "Policy",
                java.util.stream.IntStream.range(0, 5).mapToObj(i -> new SearchPage.Passage(i,
                        i == 2 ? "MATCHING PASSAGE" : "Distant context ".repeat(400), "[]")).toList(), 0, 5, false));
        try (var tool = tool(90)) {
            String answer = tool.searchKnowledge(List.of("policy"));
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
            assertThrows(CancellationException.class, () -> tool.searchKnowledge(List.of("policy")));
            verify(search, never()).expand(any(), any(), anyInt());
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
        when(search.expand(any(), eq(first), eq(2))).thenReturn(new SearchDocument(document, generation, "Policy",
                List.of(new SearchPage.Passage(1, "This contract is for PROJECT Y, not PROJECT X.", "[]"),
                        new SearchPage.Passage(2, "The quoted fee is 100000.", "[]")), 1, 3, false));
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenAnswer(call -> {
            assertTrue(call.<String>getArgument(0).contains("PROJECT Y"));
            verify(search).expand(any(), eq(first), eq(2));
            return new SearchTool.ContextSelection(SearchTool.Expansion.NOT_RELEVANT);
        });
        try (var tool = tool(8000)) {
            assertTrue(tool.searchKnowledge(List.of("PROJECT X fee")).startsWith("No relevant evidence"));
            assertTrue(events.stream().noneMatch(e -> e.source() != null));
        }
    }

    @Test
    void fullDocumentClassificationFetchesOnlyTheWiderBoundedWindow() {
        candidates();
        when(runner.createObject(anyString(), eq(SearchTool.Selection.class))).thenReturn(new SearchTool.Selection(List.of(1)));
        when(runner.createObject(anyString(), eq(SearchTool.ContextSelection.class))).thenReturn(
                new SearchTool.ContextSelection(SearchTool.Expansion.FULL_DOCUMENT));
        when(search.expand(any(), eq(first), eq(5))).thenReturn(new SearchDocument(document, generation, "Policy",
                java.util.stream.IntStream.range(0, 8).mapToObj(i -> new SearchPage.Passage(i, "Context " + i, "[]")).toList(), 0, 8, false));
        try (var tool = tool(8000)) {
            String answer = tool.searchKnowledge(List.of("policy"));
            assertTrue(answer.contains("Context 7"));
            var ordered = inOrder(search);
            ordered.verify(search).ranked(any(), any(), any());
            ordered.verify(search).expand(any(), eq(first), eq(2));
            ordered.verify(search).expand(any(), eq(first), eq(5));
            verifyNoMoreInteractions(search);
        }
    }

    @Test
    void stopDuringQueryRewritePreventsKeywordInferenceAndRetrieval() {
        when(runner.createObject(anyList(), eq(SearchTool.SemanticQuery.class))).thenAnswer(ignored -> {
            stopped.set(true);
            throw new IllegalStateException("native request interrupted");
        });
        try (var tool = tool(8000)) {
            assertThrows(CancellationException.class, () -> tool.searchKnowledge(List.of("policy")));
            verify(runner, never()).createObject(anyList(), eq(SearchTool.KeywordQueries.class));
            verifyNoInteractions(search);
        }
    }

    @Test
    void followUpRewritesUseHistoryAndAreCachedWhileToolQueriesKeepTheirOwnWeight() {
        var result = mock(SearchResults.class);
        when(result.hits()).thenReturn(List.of());
        when(search.ranked(any(), any(), any())).thenAnswer(call -> {
            List<io.memoryos.retrieval.SearchQuery> queries = call.getArgument(1);
            assertTrue(queries.stream().anyMatch(q -> q.text().equals("AX-7 onboarding") && q.weight() == 1.3 && !q.keyword()));
            assertTrue(queries.stream().anyMatch(q -> q.text().equals("AX-7") && q.weight() == 1.0 && q.keyword()));
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
                new ChatSearchProperties(12, 5, 6000, 8000, 3), () -> {}, () -> 8000, events::add, Mono.never(),
                List.of(new UserMessage("Tell me about AX-7"), new AssistantMessage("AX-7 is our internal system."),
                        new UserMessage("How do I set it up?")))) {
            tool.beforeToolCall(new BeforeToolCallContext(new ToolCall("follow-up", "searchKnowledge", "{}")));
            tool.searchKnowledge(List.of("setup instructions"));
            tool.searchKnowledge(List.of("setup instructions"));
            verify(runner).createObject(anyList(), eq(SearchTool.SemanticQuery.class));
            verify(runner).createObject(anyList(), eq(SearchTool.KeywordQueries.class));
        }
    }
}
