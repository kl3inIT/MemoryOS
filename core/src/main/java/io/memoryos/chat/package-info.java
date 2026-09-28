@ApplicationModule(displayName = "Chat", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"shared", "ai", "library", "iam", "retrieval", "connector", "objectstorage", "document", "mcp", "usage", "audit"})
@NullMarked
package io.memoryos.chat;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
