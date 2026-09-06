package io.memoryos.iam.persistence;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantMembershipRole;
import io.memoryos.iam.TenantMembershipStatus;
import io.memoryos.iam.TenantStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Repository;

@Repository
public class JpaTenantRepository {

    private final EntityManager entityManager;

    public JpaTenantRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    public TenantBootstrapStateEntity lockBootstrapState() {
        TenantBootstrapStateEntity state = entityManager.find(
                TenantBootstrapStateEntity.class,
                TenantBootstrapStateEntity.SINGLETON_ID,
                LockModeType.PESSIMISTIC_WRITE
        );
        if (state == null) {
            throw new IllegalStateException("Tenant bootstrap singleton row is missing");
        }
        return state;
    }

    public long countTenants() {
        return entityManager.createQuery("select count(tenant) from TenantEntity tenant", Long.class)
                .getSingleResult();
    }

    public Optional<TenantEntity> findTenant(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return Optional.ofNullable(entityManager.find(TenantEntity.class, tenantId.value()));
    }

    public Optional<TenantEntity> findActiveTenant(TenantId tenantId) {
        return findTenant(tenantId).filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE);
    }

    public Optional<ActorEntity> findActor(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return Optional.ofNullable(entityManager.find(ActorEntity.class, actorId.value()));
    }

    public Optional<TenantMembershipEntity> findMembership(TenantId tenantId, ActorId actorId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        return Optional.ofNullable(entityManager.find(
                TenantMembershipEntity.class,
                new TenantMembershipId(tenantId.value(), actorId.value())
        ));
    }

    public Optional<TenantMembershipEntity> findMembershipLocked(TenantId tenantId, ActorId actorId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        return Optional.ofNullable(entityManager.find(
                TenantMembershipEntity.class,
                new TenantMembershipId(tenantId.value(), actorId.value()),
                LockModeType.PESSIMISTIC_WRITE
        ));
    }

    public List<TenantMembershipEntity> findActiveMemberships(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return entityManager.createQuery("""
                        select membership
                        from TenantMembershipEntity membership
                        join fetch membership.tenant tenant
                        where membership.actor.id = :actorId
                          and membership.status = :membershipStatus
                          and tenant.status = :tenantStatus
                        """, TenantMembershipEntity.class)
                .setParameter("actorId", actorId.value())
                .setParameter("membershipStatus", TenantMembershipStatus.ACTIVE)
                .setParameter("tenantStatus", TenantStatus.ACTIVE)
                .getResultList();
    }

    public Optional<TenantMembershipEntity> findActiveOwner(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        List<TenantMembershipEntity> owners = entityManager.createQuery("""
                        select membership
                        from TenantMembershipEntity membership
                        join fetch membership.actor
                        where membership.tenant.id = :tenantId
                          and membership.role = :ownerRole
                          and membership.status = :activeStatus
                        """, TenantMembershipEntity.class)
                .setParameter("tenantId", tenantId.value())
                .setParameter("ownerRole", TenantMembershipRole.OWNER)
                .setParameter("activeStatus", TenantMembershipStatus.ACTIVE)
                .getResultList();
        if (owners.size() > 1) {
            throw new IllegalStateException("Tenant has more than one active owner");
        }
        return owners.stream().findFirst();
    }

    public boolean hasAnyMembership(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return entityManager.createQuery("""
                        select count(membership)
                        from TenantMembershipEntity membership
                        where membership.actor.id = :actorId
                        """, Long.class)
                .setParameter("actorId", actorId.value())
                .getSingleResult() != 0;
    }

    public void persist(TenantEntity tenant) {
        entityManager.persist(Objects.requireNonNull(tenant, "tenant must not be null"));
    }

    public void persist(TenantMembershipEntity membership) {
        entityManager.persist(Objects.requireNonNull(membership, "membership must not be null"));
    }

    public void flush() {
        entityManager.flush();
    }
}
