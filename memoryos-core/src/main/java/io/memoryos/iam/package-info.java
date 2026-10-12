@ApplicationModule(
        displayName = "IAM",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "audit"}
)
@NullMarked
package io.memoryos.iam;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
