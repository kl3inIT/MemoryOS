/**
 * The Tenant's audit stream: what authority changed, who changed it and when (ADR 0013).
 *
 * <p>Audit is written by every capability that changes authority or configuration, IAM included, so it sits below
 * them and depends only on the shared kernel for {@code TenantId} and {@code ActorId}. The one IAM decision it needs,
 * who may read the stream, reaches it through {@link io.memoryos.audit.AuditReaders}, which IAM implements. The package root is
 * the published API; SQL and row mapping stay in {@code persistence}.
 */
@ApplicationModule(displayName = "Audit", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"shared"})
@NullMarked
package io.memoryos.audit;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
