@ApplicationModule(
        displayName = "Retrieval",
        type = ApplicationModule.Type.CLOSED,
        // objectstorage: DocumentOriginalService streams the stored original behind a readable Document.
        allowedDependencies = {"shared", "document", "connector", "objectstorage", "iam", "usage"}
)
@NullMarked
package io.memoryos.retrieval;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
