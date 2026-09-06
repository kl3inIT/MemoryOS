@ApplicationModule(
        displayName = "Document",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"tenant", "objectstorage"}
)
package io.memoryos.document;

import org.springframework.modulith.ApplicationModule;
