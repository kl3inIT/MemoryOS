@ApplicationModule(
        displayName = "Tenant",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"identity"}
)
package io.memoryos.tenant;

import org.springframework.modulith.ApplicationModule;
