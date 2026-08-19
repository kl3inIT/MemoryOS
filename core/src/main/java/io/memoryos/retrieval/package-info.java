@ApplicationModule(
        displayName = "Retrieval",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"authorization", "knowledge"}
)
package io.memoryos.retrieval;

import org.springframework.modulith.ApplicationModule;
