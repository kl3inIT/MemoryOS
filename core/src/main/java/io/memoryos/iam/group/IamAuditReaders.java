package io.memoryos.iam.group;

import io.memoryos.audit.AuditReaders;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/** The audit stream is read by whoever holds {@link IamCapability#AUDIT_READ} in their Tenant, never scoped. */
@Component
@NullMarked
public class IamAuditReaders implements AuditReaders {
    private final IamAuthorization authorization;

    public IamAuditReaders(IamAuthorization authorization) {
        this.authorization = authorization;
    }

    @Override
    public TenantId requireReader(ActorId reader) {
        return authorization.require(reader, IamCapability.AUDIT_READ, false).tenantId();
    }
}
