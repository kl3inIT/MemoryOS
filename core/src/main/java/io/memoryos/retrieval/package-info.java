@ApplicationModule(
        displayName = "Retrieval",
        type = ApplicationModule.Type.CLOSED,
        // objectstorage: DocumentOriginalService streams the stored original PDF behind a readable Document.
        allowedDependencies = {"document", "connector", "objectstorage", "iam :: *"}
)
package io.memoryos.retrieval;

import org.springframework.modulith.ApplicationModule;
