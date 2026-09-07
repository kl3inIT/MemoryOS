@ApplicationModule(
        displayName = "Ingestion",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"connector", "document", "iam", "objectstorage"}
)
package io.memoryos.ingestion;

import org.springframework.modulith.ApplicationModule;
