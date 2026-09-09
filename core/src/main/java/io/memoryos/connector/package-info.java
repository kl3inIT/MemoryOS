@ApplicationModule(
        displayName = "Connector",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"iam", "document", "objectstorage"}
)
package io.memoryos.connector;

import org.springframework.modulith.ApplicationModule;
