package io.memoryos.audit;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

/**
 * Who may read a Tenant's audit stream. IAM decides it and implements this; audit cannot ask IAM directly, because IAM
 * records its own changes through {@link AuditTrail} and the two modules would depend on each other.
 */
public interface AuditReaders {

    /**
     * The Tenant whose stream {@code reader} may read. Refuses, with IAM's own failure, anyone who may not read it.
     */
    TenantId requireReader(ActorId reader);
}
