@ApplicationModule(displayName = "AI usage", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"shared", "iam", "objectstorage", "audit"})
package io.memoryos.usage;

import org.springframework.modulith.ApplicationModule;
