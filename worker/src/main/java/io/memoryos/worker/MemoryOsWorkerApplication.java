package io.memoryos.worker;

import io.memoryos.chat.application.ChatWorkerComponents;
import io.memoryos.retrieval.SearchTimings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

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
// MEM-9, MEM-142, MEM-143, MEM-152 and MEM-153: the Chat work the Worker runs, named by Chat itself.
@Import({SearchTimings.class, ChatWorkerComponents.class})
public class MemoryOsWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryOsWorkerApplication.class, args);
    }
}
