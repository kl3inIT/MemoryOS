@ApplicationModule(
        displayName = "Identity",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"audit"}
)
package io.memoryos.identity;

import org.springframework.modulith.ApplicationModule;
