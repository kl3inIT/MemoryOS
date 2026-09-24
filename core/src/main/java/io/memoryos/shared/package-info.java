/**
 * The shared kernel: identifier types that every capability carries and none of them owns. Only pure values belong
 * here, with no behaviour and no dependency on a capability; an identifier that names one module's aggregate stays in
 * that module (ADR 0015).
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.CLOSED, allowedDependencies = {})
@org.jspecify.annotations.NullMarked
package io.memoryos.shared;

import org.springframework.modulith.ApplicationModule;
