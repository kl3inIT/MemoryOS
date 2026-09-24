@ApplicationModule(
        displayName = "IAM",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "audit"}
)
package io.memoryos.iam;

import org.springframework.modulith.ApplicationModule;
