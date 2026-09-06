@ApplicationModule(
        displayName = "Connector",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"identity", "tenant", "document", "objectstorage"}
)
package io.memoryos.connector;

import org.springframework.modulith.ApplicationModule;
