/**
 * The shared kernel: identifier types that every capability carries and none of them owns, and a few technical
 * utilities that several capabilities repeated word for word (the leased-job pass, SHA-256 hex, LIKE escaping and PDF
 * text). Nothing here knows a capability or its data; an identifier that names one module's aggregate, or a rule that
 * belongs to one module, stays in that module (ADR 0015, widened by the phase 3 owner decision of 2026-09-25).
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.CLOSED, allowedDependencies = {})
@org.jspecify.annotations.NullMarked
package io.memoryos.shared;

import org.springframework.modulith.ApplicationModule;
