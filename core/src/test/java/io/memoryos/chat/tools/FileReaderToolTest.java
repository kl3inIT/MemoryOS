package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.memoryos.chat.ChatEvidence;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatSource;
import io.memoryos.library.UserFileService;
import io.memoryos.library.UserFileSearchService;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.retrieval.SearchTasks;
import io.memoryos.retrieval.SearchUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

class FileReaderToolTest {
    @Test
    void indexedPassagesInOneFileHaveDistinctCitationPositions() {
        var evidence = new ChatEvidence();
        var id = UUID.randomUUID();
        var generation = UUID.randomUUID();
        var first = evidence.file(id, "Table", "text/csv", new ChatSource.FileLocation(null, null, generation, 5));
        var second = evidence.file(id, "Table", "text/csv", new ChatSource.FileLocation(null, null, generation, 8));
        assertNotNull(first); assertNotNull(second);
        assertNotEquals(first.citationId(), second.citationId());
        assertNotNull(second.fileLocation());
        assertEquals(8, second.fileLocation().ordinal());
        assertSame(first, evidence.file(id, "Table", "text/csv", new ChatSource.FileLocation(null, null, generation, 5)));
        assertThrows(IllegalArgumentException.class, () -> new ChatSource.FileLocation(0, 1, generation, 1));
    }
    @Test
    void dependencyFailureSuggestsCachedReaderButAuthorizationAndStopAreNotSwallowed() {
        var files = mock(UserFileService.class);
        var search = mock(UserFileSearchService.class);
        var actor = new ActorId(UUID.randomUUID());
        var tenant = new TenantId(UUID.randomUUID());
        var allowed = Set.of(UUID.randomUUID());
        try (var scope = new SearchTasks.Scope(Duration.ofSeconds(1))) {
            var tool = new FileReaderTool(files, actor, tenant, allowed, () -> {}, () -> 4000,
                    new JTokkitTokenCountEstimator(),
                    search, new ChatEvidence(), scope);
            when(search.search(actor, tenant, allowed, "query")).thenThrow(new SearchUnavailableException());
            assertTrue(tool.searchFiles("query").contains("Use read_file"));
            doThrow(ChatException.unavailable()).when(search).search(actor, tenant, allowed, "query");
            assertThrows(ChatException.class, () -> tool.searchFiles("query"));
            doThrow(new CancellationException()).when(search).search(actor, tenant, allowed, "query");
            assertThrows(CancellationException.class, () -> tool.searchFiles("query"));
            assertEquals("File unavailable.", tool.readFile(UUID.randomUUID().toString(), 0, 50));
            verifyNoInteractions(files);
        }
    }
}
