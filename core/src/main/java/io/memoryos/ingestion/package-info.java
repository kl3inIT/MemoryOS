@ApplicationModule(
        displayName = "Ingestion",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"connector", "document", "iam", "objectstorage", "retrieval"}
)
package io.memoryos.ingestion;

import org.springframework.modulith.ApplicationModule;
