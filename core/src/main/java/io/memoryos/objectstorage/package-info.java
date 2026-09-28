@ApplicationModule(
        displayName = "Object Storage",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared"}
)
@NullMarked
package io.memoryos.objectstorage;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
