@ApplicationModule(
        displayName = "Object Storage",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"iam", "iam :: *"}
)
package io.memoryos.objectstorage;

import org.springframework.modulith.ApplicationModule;
