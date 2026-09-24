package io.memoryos.chat.application;

import io.memoryos.chat.persistence.JdbcChatArtifactCleanupRepository;
import io.memoryos.chat.persistence.JdbcChatExportRepository;
import io.memoryos.chat.persistence.JdbcChatLibraryArchiveRepository;
import io.memoryos.chat.persistence.JdbcChatLibraryRepository;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcChatSessionPurgeRepository;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.chat.persistence.JdbcUserFileWorkRepository;
import org.springframework.context.annotation.Import;

/**
 * The Chat beans the Worker runs, for a Worker that does not scan Chat: file extraction work (MEM-9), the upload
 * and trash upkeep, the artifact byte-release sweep (MEM-142), purging deleted conversations (MEM-143), exports
 * (MEM-153) and library archives (MEM-152). The Worker imports this class; it is deliberately not a component, so
 * the API, which scans all of {@code io.memoryos}, registers these beans only once.
 */
@Import({
        JdbcUserFileWorkRepository.class,
        DefaultUserFileWorkService.class,
        JdbcUserFileRepository.class,
        UserFileMaintenance.class,
        JdbcChatArtifactCleanupRepository.class,
        ChatArtifactCleanupService.class,
        JdbcChatSessionPurgeRepository.class,
        ChatSessionPurgeService.class,
        JdbcChatExportRepository.class,
        JdbcChatRepository.class,
        ChatExportService.class,
        JdbcChatLibraryArchiveRepository.class,
        JdbcChatLibraryRepository.class,
        ChatLibraryArchiveService.class
})
public class ChatWorkerComponents {
}
