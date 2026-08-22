@ApplicationModule(
        displayName = "Invitation",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"identity", "organization"}
)
package io.memoryos.invitation;

import org.springframework.modulith.ApplicationModule;
