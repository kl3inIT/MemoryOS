@ApplicationModule(
        displayName = "Invitation",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"identity", "tenant"}
)
package io.memoryos.invitation;

import org.springframework.modulith.ApplicationModule;
