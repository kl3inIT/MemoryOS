@ApplicationModule(
        displayName = "Retrieval",
        type = ApplicationModule.Type.CLOSED,
        // objectstorage: DocumentOriginalService streams the stored original behind a readable Document.
        allowedDependencies = {"shared", "document", "connector", "objectstorage", "iam :: tenant", "iam :: group", "usage"}
)
package io.memoryos.retrieval;

import org.springframework.modulith.ApplicationModule;
