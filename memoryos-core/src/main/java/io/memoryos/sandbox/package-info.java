/**
 * The Chat sandbox (MEM-270, ADR 0029): one runtime sandbox a conversation, recorded in PostgreSQL, renewed on use
 * and replaced when it is gone. The runtime itself is {@link io.memoryos.sandbox.SandboxRuntime}, implemented by an
 * integration module.
 */
@ApplicationModule(
        displayName = "Sandbox",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared"}
)
@NullMarked
package io.memoryos.sandbox;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
