package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.memoryos.chat.ChatEvidence;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatFileService;
import io.memoryos.chat.ChatFileSearchService;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;
import io.memoryos.retrieval.SearchTasks;
import io.memoryos.retrieval.SearchUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileReaderToolTest {
    @Test
    void dependencyFailureSuggestsCachedReaderButAuthorizationAndStopAreNotSwallowed() {
        var files = mock(ChatFileService.class);
        var search = mock(ChatFileSearchService.class);
        var actor = new ActorId(UUID.randomUUID());
        var tenant = new TenantId(UUID.randomUUID());
        var allowed = Set.of(UUID.randomUUID());
        try (var scope = new SearchTasks.Scope(Duration.ofSeconds(1))) {
            var tool = new FileReaderTool(files, actor, tenant, allowed, () -> {}, () -> 4000,
                    new org.springframework.ai.tokenizer.JTokkitTokenCountEstimator(),
                    search, new ChatEvidence(), scope, Instant.now().plusSeconds(30));
            when(search.search(actor, tenant, allowed, "query")).thenThrow(new SearchUnavailableException());
            assertTrue(tool.search_files("query").contains("Use read_file"));
            doThrow(ChatException.unavailable()).when(search).search(actor, tenant, allowed, "query");
            assertThrows(ChatException.class, () -> tool.search_files("query"));
            doThrow(new java.util.concurrent.CancellationException()).when(search).search(actor, tenant, allowed, "query");
            assertThrows(java.util.concurrent.CancellationException.class, () -> tool.search_files("query"));
            assertEquals("File unavailable.", tool.read_file(UUID.randomUUID().toString(), 0, 50));
            verifyNoInteractions(files);
        }
    }
}
