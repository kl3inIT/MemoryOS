@ApplicationModule(
        displayName = "Authorization",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"identity"}
)
package io.memoryos.authorization;

import org.springframework.modulith.ApplicationModule;
