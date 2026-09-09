package io.memoryos.iam.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public final class GroupMembershipId implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    protected GroupMembershipId() {
    }

    public GroupMembershipId(UUID tenantId, UUID groupId, UUID actorId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.groupId = Objects.requireNonNull(groupId, "groupId must not be null");
        this.actorId = Objects.requireNonNull(actorId, "actorId must not be null");
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID groupId() {
        return groupId;
    }

    public UUID actorId() {
        return actorId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public UUID getActorId() {
        return actorId;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        return candidate instanceof GroupMembershipId other
                && tenantId.equals(other.tenantId)
                && groupId.equals(other.groupId)
                && actorId.equals(other.actorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, groupId, actorId);
    }
}
