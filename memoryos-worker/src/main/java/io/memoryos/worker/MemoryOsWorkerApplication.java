package io.memoryos.worker;

import io.memoryos.ai.ocr.OcrWorkerComponents;
import io.memoryos.audit.AuditWorkerComponents;
import io.memoryos.channel.ChannelWorkerComponents;
import io.memoryos.chat.ChatWorkerComponents;
import io.memoryos.connector.ConnectorWorkerComponents;
import io.memoryos.document.DocumentWorkerComponents;
import io.memoryos.iam.IamWorkerComponents;
import io.memoryos.ingestion.IngestionWorkerComponents;
import io.memoryos.library.LibraryWorkerComponents;
import io.memoryos.mcp.McpWorkerComponents;
import io.memoryos.objectstorage.ObjectStorageWorkerComponents;
import io.memoryos.retrieval.RetrievalWorkerComponents;
import io.memoryos.sandbox.SandboxWorkerComponents;
import io.memoryos.usage.UsageWorkerComponents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * The Worker scans only its own package. What it runs of {@code core} is this list: each module publishes one
 * {@code WorkerComponents} class naming its beans, entities and repositories, so a module that gains a bean changes
 * nothing here, and {@code WorkerDependencyRulesTest} keeps the Worker from naming a module's internals (MEM-244).
 */
@SpringBootApplication
@Import({
        AuditWorkerComponents.class,
        ChannelWorkerComponents.class,
        ChatWorkerComponents.class,
        ConnectorWorkerComponents.class,
        DocumentWorkerComponents.class,
        IamWorkerComponents.class,
        IngestionWorkerComponents.class,
        LibraryWorkerComponents.class,
        McpWorkerComponents.class,
        ObjectStorageWorkerComponents.class,
        OcrWorkerComponents.class,
        RetrievalWorkerComponents.class,
        SandboxWorkerComponents.class,
        UsageWorkerComponents.class
})
public class MemoryOsWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryOsWorkerApplication.class, args);
    }
}
