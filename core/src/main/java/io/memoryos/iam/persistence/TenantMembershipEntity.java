package io.memoryos.iam.persistence;

import io.memoryos.iam.TenantMembershipRole;
import io.memoryos.iam.TenantMembershipStatus;

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

@Entity
@Table(name = "tenant_memberships")
public class TenantMembershipEntity {

    @EmbeddedId
    private TenantMembershipId id;

    @MapsId("tenantId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private TenantEntity tenant;

    @MapsId("actorId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false, updatable = false)
    private ActorEntity actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private TenantMembershipRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TenantMembershipStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected TenantMembershipEntity() {
    }

    public TenantMembershipEntity(
            TenantEntity tenant,
            ActorEntity actor,
            TenantMembershipRole role,
            TenantMembershipStatus status
    ) {
        this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        this.actor = Objects.requireNonNull(actor, "actor must not be null");
        this.id = new TenantMembershipId(tenant.getId(), actor.getId());
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public TenantMembershipId getId() {
        return id;
    }
    public UUID getTenantId() {
        return id.tenantId();
    }

    public UUID getActorId() {
        return id.actorId();
    }


    public TenantEntity getTenant() {
        return tenant;
    }

    public ActorEntity getActor() {
        return actor;
    }

    public TenantMembershipRole getRole() {
        return role;
    }

    public TenantMembershipStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == TenantMembershipStatus.ACTIVE;
    }

    public void changeStatus(TenantMembershipStatus status, Instant updatedAt) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
