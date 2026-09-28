@ApplicationModule(displayName = "MCP", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"shared", "iam", "audit"})
@NullMarked
package io.memoryos.mcp;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
