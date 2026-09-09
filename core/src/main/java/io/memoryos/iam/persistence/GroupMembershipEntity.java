package io.memoryos.iam.persistence;

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
@Table(name = "iam_group_memberships")
public class GroupMembershipEntity {

    @EmbeddedId
    private GroupMembershipId id;

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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "tenant_id",
                    referencedColumnName = "tenant_id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "actor_id",
                    referencedColumnName = "actor_id",
                    insertable = false,
                    updatable = false
            )
    })
    private TenantMembershipEntity tenantMembership;

    @Column(name = "is_manager", nullable = false)
    private boolean manager;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected GroupMembershipEntity() {
    }

    public GroupMembershipEntity(
            GroupEntity group,
            TenantMembershipEntity tenantMembership,
            boolean manager
    ) {
        this.group = Objects.requireNonNull(group, "group must not be null");
        this.tenantMembership = Objects.requireNonNull(
                tenantMembership,
                "tenantMembership must not be null"
        );
        TenantMembershipId membershipId = tenantMembership.getId();
        if (!group.getTenantId().equals(membershipId.tenantId())) {
            throw new IllegalArgumentException("group and Tenant membership must belong to the same Tenant");
        }
        this.id = new GroupMembershipId(
                group.getTenantId(),
                group.getId(),
                membershipId.actorId()
        );
        this.manager = manager;
    }

    public GroupMembershipId getId() {
        return id;
    }

    public GroupEntity getGroup() {
        return group;
    }

    public boolean isManager() {
        return manager;
    }

    public void setManager(boolean manager) {
        this.manager = manager;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
