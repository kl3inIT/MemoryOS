package io.memoryos.chat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.tool.Tool;
import io.memoryos.chat.ChatCommand;
import io.memoryos.chat.ChatEvidence;
import io.memoryos.chat.ChatSearchEvent;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.WebSearchMode;
import io.memoryos.chat.tools.WebTools;
import io.memoryos.retrieval.SearchTasks;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

class WebToolsTest {
    @Test void queryBatchRunsInParallelKeepsPartialEvidenceAndDoesNotRepeatDuplicates() throws Exception {
        var client = mock(WebProviderClient.class);
        var connection = new WebConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), WebProvider.BRAVE, "", "", null, 1);
        var started = new CountDownLatch(2);
        when(client.search(eq(connection), anyString())).thenAnswer(call -> {
            started.countDown();
            assertTrue(started.await(3, TimeUnit.SECONDS), "Batch calls should overlap");
            if (call.<String>getArgument(1).equals("unavailable")) throw new IOException("secret upstream error");
            return List.of(new WebProviderClient.Result("https://example.com", "Good page", "Verified excerpt"));
        });
        var evidence = new ChatEvidence();
        var events = new ArrayList<ChatSearchEvent>();
        try (var scope = new SearchTasks.Scope(Duration.ofSeconds(1))) {
            var tools = new WebTools(client, new WebConnectionService.Access(connection, null), evidence, () -> {}, scope,
                    Instant.now().plusSeconds(10), events::add, () -> 5000, new JTokkitTokenCountEstimator());
            String result = tools.webSearch(List.of("news", "unavailable", "news"));
            assertTrue(result.contains("Verified excerpt"));
            assertTrue(result.contains("1 request(s) failed"));
            assertFalse(result.contains("secret upstream"));
            assertEquals(1, evidence.snapshot().size());
            assertEquals(ChatSearchEvent.Stage.COMPLETED, events.getLast().stage());
            assertTrue(tools.webSearch(List.of("news")).contains("already attempted"));
            verify(client, times(1)).search(connection, "news");
            verify(client, times(1)).search(connection, "unavailable");
        }
    }
    @Test void urlBatchDeduplicatesAndRejectsInvalidBatchesBeforeIo() throws Exception {
        var client = mock(WebProviderClient.class);
        when(client.read(isNull(), anyString(), any())).thenAnswer(call ->
                new WebProviderClient.Result(call.getArgument(1), "Page", "Page content"));
        try (var scope = new SearchTasks.Scope(Duration.ofSeconds(1))) {
            var evidence = new ChatEvidence();
            var tools = new WebTools(client, new WebConnectionService.Access(null, null), evidence, () -> {}, scope,
                    Instant.now().plusSeconds(10), ignored -> {}, () -> 5000, new JTokkitTokenCountEstimator());
            assertTrue(tools.openUrl(List.of()).contains("one to five"));
            assertTrue(tools.openUrl(Collections.nCopies(6, "https://example.com")).contains("one to five"));
            verifyNoInteractions(client);
            var registered = Tool.fromInstance(tools);
            assertEquals(Set.of("web_search", "open_url"), registered.stream().map(tool -> tool.getDefinition().getName()).collect(Collectors.toSet()));
            var reader = registered.stream().filter(tool -> tool.getDefinition().getName().equals("open_url")).findFirst().orElseThrow();
            reader.call("{\"urls\":[\"https://example.com/a\",\"https://example.com/b\",\"https://example.com/a\"]}");
            assertEquals(2, evidence.snapshot().size());
            assertEquals("https://example.com/a", Objects.requireNonNull(evidence.snapshot().getFirst().web()).url());
            verify(client, times(2)).read(isNull(), anyString(), any());
        }
    }
    @Test void toolResultsShareCitationNamespaceDeduplicateAndPublishActualProgress() throws Exception {
        var client = mock(WebProviderClient.class);
        var connection = new WebConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), WebProvider.BRAVE, "", "", null, 1);
        when(client.search(connection, "news")).thenReturn(List.of(new WebProviderClient.Result("https://example.com", "Title", "Verified text")));
        var evidence = new ChatEvidence(); evidence.file(UUID.randomUUID(), "Existing file", "text/plain");
        var events = new ArrayList<ChatSearchEvent>(); evidence.publishTo(events::add);
        try (var scope = new SearchTasks.Scope(Duration.ofSeconds(1))) {
            var tools = new WebTools(client, new WebConnectionService.Access(connection, null), evidence, () -> {}, scope,
                    Instant.now().plusSeconds(10), events::add, () -> 5000, new JTokkitTokenCountEstimator());
            assertTrue(tools.webSearch(List.of("news")).contains("[2]"));
            assertTrue(tools.webSearch(List.of("news")).contains("already attempted"));
            verify(client, times(1)).search(connection, "news");
        }
        var web = evidence.snapshot().get(1).web();
        assertNotNull(web);
        assertEquals("https://example.com", web.url());
        assertEquals(ChatSearchEvent.Stage.COMPLETED, events.getLast().stage());
        assertTrue(events.stream().anyMatch(event -> event.stage() == ChatSearchEvent.Stage.SEARCHING && event.search() != null && event.search().queries().equals(List.of("news"))));
    }
    @Test void legacyCommandIsOfflineAndWebEvidenceCannotPretendToBeDocument() {
        var command = new ChatCommand(ChatCommand.Operation.SEND, UUID.randomUUID(), UUID.randomUUID(), "Question", null);
        assertEquals(WebSearchMode.off, command.webSearch());
        assertThrows(IllegalArgumentException.class, () -> new ChatSource(1, UUID.randomUUID(), UUID.randomUUID(), "Title", 0, 0,
                List.of(), null, null, new ChatSource.WebLocation("https://example.com", "Excerpt", Instant.now())));
    }
    @Test void stoppedToolNeverStartsNetworkIo() {
        var client = mock(WebProviderClient.class);
        try (var scope = new SearchTasks.Scope(Duration.ofSeconds(1))) {
            var tools = new WebTools(client, new WebConnectionService.Access(null, null), new ChatEvidence(),
                    () -> { throw new CancellationException(); }, scope, Instant.now().plusSeconds(5), ignored -> {},
                    () -> 5000, new JTokkitTokenCountEstimator());
            assertThrows(CancellationException.class, () -> tools.openUrl(List.of("https://example.com")));
            verifyNoInteractions(client);
        }
    }
    @Test void suppliedUrlUsesReaderWithoutSearchAndBoundsSerializedEvidence() throws Exception {
        var client = mock(WebProviderClient.class);
        when(client.read(isNull(), eq("https://example.com/article"), any())).thenReturn(
                new WebProviderClient.Result("https://example.com/article", "Article", "quoted \"text\"\n".repeat(10000)));
        var evidence = new ChatEvidence();
        var estimator = new JTokkitTokenCountEstimator();
        try (var scope = new SearchTasks.Scope(Duration.ofSeconds(1))) {
            var tools = new WebTools(client, new WebConnectionService.Access(null, null), evidence, () -> {}, scope,
                    Instant.now().plusSeconds(10), ignored -> {}, () -> 300, estimator);
            String output = tools.openUrl(List.of("https://example.com/article"));
            assertTrue(output.contains("[1]"));
            assertTrue(estimator.estimate(output) <= 300);
            assertEquals(1, evidence.snapshot().size());
            verify(client, never()).search(any(), any());
        }
    }
}
