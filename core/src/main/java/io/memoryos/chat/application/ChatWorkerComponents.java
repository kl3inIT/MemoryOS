package io.memoryos.chat.application;

import io.memoryos.chat.persistence.JdbcChatArtifactCleanupRepository;
import io.memoryos.chat.persistence.JdbcChatExportRepository;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcChatSessionPurgeRepository;
import org.springframework.context.annotation.Import;

/**
 * The Chat beans the Worker runs, for a Worker that does not scan Chat: the artifact byte-release sweep (MEM-142),
 * purging deleted conversations (MEM-143) and exports (MEM-153). File work, upload upkeep and library archives are
 * the library's ({@link io.memoryos.library.LibraryWorkerComponents}), which the Worker imports beside this. The Worker
 * imports this class; it is deliberately not a component, so the API, which scans all of {@code io.memoryos},
 * registers these beans only once.
 */
@Import({
        JdbcChatArtifactCleanupRepository.class,
        ChatArtifactCleanupService.class,
        JdbcChatSessionPurgeRepository.class,
        ChatSessionPurgeService.class,
        JdbcChatExportRepository.class,
        JdbcChatRepository.class,
        ChatExportService.class
})
public class ChatWorkerComponents {
}
