package io.memoryos.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "io.memoryos.worker",
        "io.memoryos.connector",
        "io.memoryos.objectstorage",
        "io.memoryos.document",
        "io.memoryos.ingestion",
        "io.memoryos.retrieval.embedding",
        "io.memoryos.retrieval.opensearch",
        // MEM-135: the PRESENT search generation, its embedding provider and their persistence.
        "io.memoryos.retrieval.settings",
        "io.memoryos.usage",
        "io.memoryos.iam.audit",
        "io.memoryos.iam.group.persistence",
        "io.memoryos.iam.identity.persistence",
        "io.memoryos.iam.invitation.persistence",
        "io.memoryos.iam.tenant.persistence",
        "io.memoryos.iam.user.persistence"
})
@org.springframework.context.annotation.Import({io.memoryos.retrieval.SearchTimings.class,
        io.memoryos.chat.persistence.JdbcUserFileWorkRepository.class,
        io.memoryos.chat.application.DefaultUserFileWorkService.class,
        // MEM-142: the sweep that releases the bytes of deleted Chat artifacts.
        io.memoryos.chat.persistence.JdbcChatArtifactCleanupRepository.class,
        io.memoryos.chat.application.ChatArtifactCleanupService.class,
        // MEM-143: the sweep that removes conversations their owner deleted.
        io.memoryos.chat.persistence.JdbcChatSessionPurgeRepository.class,
        io.memoryos.chat.application.ChatSessionPurgeService.class,
        // MEM-153: exporting one's own conversations and files, which reads the conversations themselves.
        io.memoryos.chat.persistence.JdbcChatExportRepository.class,
        io.memoryos.chat.persistence.JdbcChatRepository.class,
        io.memoryos.chat.application.ChatExportService.class,
        // MEM-152: packing a library selection into a ZIP and releasing expired archives.
        io.memoryos.chat.persistence.JdbcChatLibraryArchiveRepository.class,
        io.memoryos.chat.persistence.JdbcChatLibraryRepository.class,
        io.memoryos.chat.persistence.JdbcUserFileRepository.class,
        io.memoryos.chat.application.ChatLibraryArchiveService.class})
public class MemoryOsWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryOsWorkerApplication.class, args);
    }
}
