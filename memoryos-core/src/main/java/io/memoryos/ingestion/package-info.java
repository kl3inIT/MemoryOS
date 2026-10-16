@ApplicationModule(
        displayName = "Ingestion",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "connector", "document", "objectstorage", "retrieval", "library"}
)
@NullMarked
package io.memoryos.ingestion;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
