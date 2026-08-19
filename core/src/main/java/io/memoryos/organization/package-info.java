@ApplicationModule(
        displayName = "Organization",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"identity"}
)
package io.memoryos.organization;

import org.springframework.modulith.ApplicationModule;
