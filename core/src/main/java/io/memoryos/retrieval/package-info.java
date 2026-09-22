@ApplicationModule(
        displayName = "Retrieval",
        type = ApplicationModule.Type.CLOSED,
        // objectstorage: DocumentOriginalService streams the stored original behind a readable Document.
        allowedDependencies = {"document", "connector", "objectstorage", "iam :: *", "usage"}
)
package io.memoryos.retrieval;

import org.springframework.modulith.ApplicationModule;
