@ApplicationModule(
        displayName = "IAM",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"audit"}
)
package io.memoryos.iam;

import org.springframework.modulith.ApplicationModule;
