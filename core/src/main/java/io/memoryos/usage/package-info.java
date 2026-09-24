@ApplicationModule(displayName = "AI usage", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"iam :: *", "objectstorage", "audit"})
package io.memoryos.usage;

import org.springframework.modulith.ApplicationModule;
