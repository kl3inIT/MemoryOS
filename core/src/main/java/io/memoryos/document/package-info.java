@ApplicationModule(
        displayName = "Document",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "objectstorage"}
)
@NullMarked
package io.memoryos.document;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
