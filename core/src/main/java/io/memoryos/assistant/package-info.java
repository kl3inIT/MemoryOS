@ApplicationModule(
        displayName = "Assistant",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"retrieval"}
)
package io.memoryos.assistant;

import org.springframework.modulith.ApplicationModule;
