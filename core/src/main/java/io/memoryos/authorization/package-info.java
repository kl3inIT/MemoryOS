@ApplicationModule(
        displayName = "Authorization",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"identity", "audit"}
)
package io.memoryos.authorization;

import org.springframework.modulith.ApplicationModule;
