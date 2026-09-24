@ApplicationModule(displayName = "MCP", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"shared", "iam :: group", "audit"})
package io.memoryos.mcp;

import org.springframework.modulith.ApplicationModule;
