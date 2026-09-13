@ApplicationModule(
        displayName = "Retrieval",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"document", "connector", "iam", "iam :: *"}
)
package io.memoryos.retrieval;

import org.springframework.modulith.ApplicationModule;
