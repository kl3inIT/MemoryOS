@ApplicationModule(
        displayName = "Connector",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"identity", "organization", "document"}
)
package io.memoryos.connector;

import org.springframework.modulith.ApplicationModule;
