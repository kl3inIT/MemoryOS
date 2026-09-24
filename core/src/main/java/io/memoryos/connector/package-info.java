@ApplicationModule(
        displayName = "Connector",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "iam :: *", "document", "objectstorage", "audit"}
)
package io.memoryos.connector;

import org.springframework.modulith.ApplicationModule;
