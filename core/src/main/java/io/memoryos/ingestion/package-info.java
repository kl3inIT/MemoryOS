@ApplicationModule(
        displayName = "Ingestion",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"knowledge"}
)
package io.memoryos.ingestion;

import org.springframework.modulith.ApplicationModule;
