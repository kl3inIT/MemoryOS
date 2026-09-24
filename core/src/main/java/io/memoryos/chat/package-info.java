@ApplicationModule(displayName = "Chat", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"iam :: *", "retrieval", "connector", "objectstorage", "document", "mcp", "usage", "audit"})
package io.memoryos.chat;

import org.springframework.modulith.ApplicationModule;
