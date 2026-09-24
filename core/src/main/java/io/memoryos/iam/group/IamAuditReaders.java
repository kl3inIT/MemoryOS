package io.memoryos.iam.group;

import io.memoryos.audit.AuditReaders;
import io.memoryos.iam.identity.ActorId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The audit stream is read by whoever holds {@link IamCapability#AUDIT_READ} in their Tenant, never scoped. */
@Component
public class IamAuditReaders implements AuditReaders {
    private final IamAuthorization authorization;

    public IamAuditReaders(IamAuthorization authorization) {
        this.authorization = authorization;
    }

    @Override
    public UUID requireReader(UUID reader) {
        return authorization.require(new ActorId(reader), IamCapability.AUDIT_READ, false).tenantId().value();
    }
}
