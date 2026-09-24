/**
 * The Tenant's audit stream: what authority changed, who changed it and when (ADR 0013).
 *
 * <p>Audit is written by every capability that changes authority or configuration, IAM included, so it sits below
 * them and depends on none: Tenants and actors are carried as their UUIDs, and the one IAM decision it needs, who may
 * read the stream, reaches it through {@link io.memoryos.audit.AuditReaders}, which IAM implements. The package root is
 * the published API; SQL and row mapping stay in {@code persistence}.
 */
@ApplicationModule(displayName = "Audit", type = ApplicationModule.Type.CLOSED, allowedDependencies = {})
@org.jspecify.annotations.NullMarked
package io.memoryos.audit;

import org.springframework.modulith.ApplicationModule;
