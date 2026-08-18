@ApplicationModule(
        displayName = "Assistant",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"retrieval", "audit"}
)
package io.memoryos.assistant;

import org.springframework.modulith.ApplicationModule;
