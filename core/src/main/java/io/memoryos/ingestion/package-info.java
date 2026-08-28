@ApplicationModule(
        displayName = "Ingestion",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"connector", "document", "organization"}
)
package io.memoryos.ingestion;

import org.springframework.modulith.ApplicationModule;
