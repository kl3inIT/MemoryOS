@ApplicationModule(
        displayName = "Document",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"iam", "objectstorage"}
)
package io.memoryos.document;

import org.springframework.modulith.ApplicationModule;
