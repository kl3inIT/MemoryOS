package io.memoryos.iam.persistence;

import io.memoryos.iam.IamCapability;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public final class GroupCapabilityGrantId implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 32, updatable = false)
    private IamCapability capability;

    protected GroupCapabilityGrantId() {
    }

    public GroupCapabilityGrantId(UUID tenantId, UUID groupId, IamCapability capability) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.groupId = Objects.requireNonNull(groupId, "groupId must not be null");
        this.capability = Objects.requireNonNull(capability, "capability must not be null");
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID groupId() {
        return groupId;
    }

    public IamCapability capability() {
        return capability;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public IamCapability getCapability() {
        return capability;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        return candidate instanceof GroupCapabilityGrantId other
                && tenantId.equals(other.tenantId)
                && groupId.equals(other.groupId)
                && capability == other.capability;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, groupId, capability);
    }
}
