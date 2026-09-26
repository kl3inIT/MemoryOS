@ApplicationModule(
        displayName = "Connector",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "iam", "document", "objectstorage", "audit"}
)
@NullMarked
package io.memoryos.connector;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
