@ApplicationModule(
        displayName = "Retrieval",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"authorization", "knowledge", "audit"}
)
package io.memoryos.retrieval;

import org.springframework.modulith.ApplicationModule;
