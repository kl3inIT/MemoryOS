@ApplicationModule(
        displayName = "Ingestion",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"connector", "document", "iam", "objectstorage", "retrieval", "chat"}
)
package io.memoryos.ingestion;

import org.springframework.modulith.ApplicationModule;
