package io.memoryos.iam.persistence;

import io.memoryos.iam.GroupSystemKey;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "iam_groups")
public class GroupEntity {
    public static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID BASIC_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @EmbeddedId
    private GroupEntityId entityId;

    @MapsId("tenantId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private TenantEntity tenant;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "system_key", length = 16, updatable = false)
    private GroupSystemKey systemKey;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected GroupEntity() {
    }

    public GroupEntity(TenantEntity tenant, UUID id, String name, @Nullable GroupSystemKey systemKey) {
        this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        this.entityId = new GroupEntityId(tenant.getId(), id);
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.systemKey = systemKey;
    }

    public UUID getId() {
        return entityId.id();
    }

    public UUID getTenantId() {
        return entityId.tenantId();
    }

    public TenantEntity getTenant() {
        return tenant;
    }

    public String getName() {
        return name;
    }

    public @Nullable GroupSystemKey getSystemKey() {
        return systemKey;
    }

    public boolean isSystemGroup() {
        return systemKey != null;
    }

    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
