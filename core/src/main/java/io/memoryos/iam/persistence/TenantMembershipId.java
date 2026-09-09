package io.memoryos.iam.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public final class TenantMembershipId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    protected TenantMembershipId() {
    }

    public TenantMembershipId(UUID tenantId, UUID actorId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.actorId = Objects.requireNonNull(actorId, "actorId must not be null");
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID actorId() {
        return actorId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getActorId() {
        return actorId;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        return candidate instanceof TenantMembershipId other
                && tenantId.equals(other.tenantId)
                && actorId.equals(other.actorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, actorId);
    }
}
