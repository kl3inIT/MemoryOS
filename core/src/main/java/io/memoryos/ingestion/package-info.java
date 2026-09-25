@ApplicationModule(
        displayName = "Ingestion",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "connector", "document", "objectstorage", "retrieval", "library"}
)
package io.memoryos.ingestion;

import org.springframework.modulith.ApplicationModule;
