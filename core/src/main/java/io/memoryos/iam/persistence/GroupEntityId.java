package io.memoryos.iam.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public final class GroupEntityId implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    protected GroupEntityId() {
    }

    public GroupEntityId(UUID tenantId, UUID id) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.id = Objects.requireNonNull(id, "id must not be null");
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID id() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getId() {
        return id;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        return candidate instanceof GroupEntityId other
                && tenantId.equals(other.tenantId)
                && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, id);
    }
}
