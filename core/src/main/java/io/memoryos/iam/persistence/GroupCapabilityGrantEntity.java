package io.memoryos.iam.persistence;

import io.memoryos.iam.IamCapability;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "iam_group_capability_grants")
public class GroupCapabilityGrantEntity {

    @EmbeddedId
    private GroupCapabilityGrantId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "tenant_id",
                    referencedColumnName = "tenant_id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "group_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            )
    })
    private GroupEntity group;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected GroupCapabilityGrantEntity() {
    }

    public GroupCapabilityGrantEntity(GroupEntity group, IamCapability capability) {
        this.group = Objects.requireNonNull(group, "group must not be null");
        this.id = new GroupCapabilityGrantId(
                group.getTenantId(),
                group.getId(),
                Objects.requireNonNull(capability, "capability must not be null")
        );
    }

    public GroupCapabilityGrantId getId() {
        return id;
    }

    public GroupEntity getGroup() {
        return group;
    }

    public IamCapability getCapability() {
        return id.capability();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
