@ApplicationModule(displayName = "Chat", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"shared", "ai", "library", "iam :: tenant", "iam :: group", "iam :: identity", "retrieval", "connector", "objectstorage", "document", "mcp", "usage", "audit"})
package io.memoryos.chat;

import org.springframework.modulith.ApplicationModule;
