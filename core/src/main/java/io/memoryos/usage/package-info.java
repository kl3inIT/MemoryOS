@ApplicationModule(displayName = "AI usage", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"shared", "iam", "objectstorage", "audit"})
@NullMarked
package io.memoryos.usage;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
