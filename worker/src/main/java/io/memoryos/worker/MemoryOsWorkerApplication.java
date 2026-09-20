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
        "io.memoryos.usage",
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
        io.memoryos.chat.application.ChatArtifactCleanupService.class})
public class MemoryOsWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryOsWorkerApplication.class, args);
    }
}
