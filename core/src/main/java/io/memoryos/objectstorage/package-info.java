@ApplicationModule(
        displayName = "Object Storage",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"tenant"}
)
package io.memoryos.objectstorage;

import org.springframework.modulith.ApplicationModule;
