package io.memoryos.iam.persistence;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.InvitationStatus;
import io.memoryos.iam.InvitationView;
import io.memoryos.iam.TenantId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

@Repository
public class JpaInvitationRepository {

    private final EntityManager entityManager;

    public JpaInvitationRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    public void expirePending(TenantId tenantId, String email, Instant now) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(now, "now must not be null");
        entityManager.createQuery("""
                        update InvitationEntity invitation
                        set invitation.status = :expired,
                            invitation.openEmailKey = null,
                            invitation.updatedAt = :now
                        where invitation.tenant.id = :tenantId
                          and invitation.openEmailKey = :email
                          and invitation.status = :pending
                          and invitation.expiresAt <= :now
                        """)
                .setParameter("expired", InvitationStatus.EXPIRED)
                .setParameter("pending", InvitationStatus.PENDING)
                .setParameter("tenantId", tenantId.value())
                .setParameter("email", email)
                .setParameter("now", now)
                .executeUpdate();
    }

    public void expirePending(TenantId tenantId, Instant now) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        entityManager.createQuery("""
                        update InvitationEntity invitation
                        set invitation.status = :expired,
                            invitation.openEmailKey = null,
                            invitation.updatedAt = :now
                        where invitation.tenant.id = :tenantId
                          and invitation.status = :pending
                          and invitation.expiresAt <= :now
                        """)
                .setParameter("expired", InvitationStatus.EXPIRED)
                .setParameter("pending", InvitationStatus.PENDING)
                .setParameter("tenantId", tenantId.value())
                .setParameter("now", now)
                .executeUpdate();
    }

    public InvitationEntity create(
            UUID invitationId,
            TenantId tenantId,
            String email,
            String digest,
            ActorId creatorActorId,
            Instant now,
            Instant expiresAt
    ) {
        TenantEntity tenant = entityManager.find(TenantEntity.class, tenantId.value());
        ActorEntity creator = entityManager.find(ActorEntity.class, creatorActorId.value());
        if (tenant == null || creator == null) {
            throw new IllegalStateException("invitation Tenant or creator Actor is missing");
        }
        InvitationEntity invitation = new InvitationEntity(
                invitationId,
                tenant,
                email,
                digest,
                creator,
                now,
                expiresAt
        );
        entityManager.persist(invitation);
        entityManager.flush();
        return invitation;
    }


    public Optional<InvitationEntity> find(TenantId tenantId, UUID invitationId) {
        return find(tenantId, invitationId, null);
    }

    public Optional<InvitationEntity> findLocked(TenantId tenantId, UUID invitationId) {
        return find(tenantId, invitationId, LockModeType.PESSIMISTIC_WRITE);
    }

    public Optional<InvitationEntity> findByDigest(String digest) {
        List<InvitationEntity> invitations = entityManager.createQuery("""
                        select invitation
                        from InvitationEntity invitation
                        where invitation.secretDigest = :digest
                        """, InvitationEntity.class)
                .setParameter("digest", Objects.requireNonNull(digest, "digest must not be null"))
                .setMaxResults(2)
                .getResultList();
        if (invitations.size() > 1) {
            throw new IllegalStateException("invitation digest is not unique");
        }
        return invitations.stream().findFirst();
    }

    public List<InvitationEntity> findPendingByEmail(String email, Instant now) {
        return entityManager.createQuery("""
                        select invitation
                        from InvitationEntity invitation
                        where invitation.openEmailKey = :email
                          and invitation.status = :pending
                          and invitation.expiresAt > :now
                        order by invitation.id
                        """, InvitationEntity.class)
                .setParameter("email", Objects.requireNonNull(email, "email must not be null"))
                .setParameter("pending", InvitationStatus.PENDING)
                .setParameter("now", Objects.requireNonNull(now, "now must not be null"))
                .setMaxResults(2)
                .getResultList();
    }

    public ActorEntity requireActor(ActorId actorId) {
        ActorEntity actor = entityManager.find(
                ActorEntity.class,
                Objects.requireNonNull(actorId, "actorId must not be null").value()
        );
        if (actor == null) {
            throw new IllegalStateException("Actor is missing");
        }
        return actor;
    }

    public void flush() {
        entityManager.flush();
    }

    public static InvitationView view(InvitationEntity invitation) {
        ActorEntity acceptedBy = invitation.getAcceptedBy();
        return new InvitationView(
                invitation.getId(),
                new TenantId(invitation.getTenant().getId()),
                invitation.getNormalizedEmail(),
                invitation.getStatus(),
                invitation.getCreatedAt(),
                invitation.getExpiresAt(),
                acceptedBy == null ? null : new ActorId(acceptedBy.getId()),
                invitation.getAcceptedAt(),
                invitation.getRevokedAt()
        );
    }

    private Optional<InvitationEntity> find(
            TenantId tenantId,
            UUID invitationId,
            LockModeType lockMode
    ) {
        TypedQuery<InvitationEntity> statement = entityManager.createQuery("""
                        select invitation
                        from InvitationEntity invitation
                        where invitation.tenant.id = :tenantId
                          and invitation.id = :invitationId
                        """, InvitationEntity.class)
                .setParameter("tenantId", Objects.requireNonNull(tenantId, "tenantId must not be null").value())
                .setParameter("invitationId", Objects.requireNonNull(invitationId, "invitationId must not be null"));
        if (lockMode == null) {
            return statement.getResultStream().findFirst();
        }
        statement.setLockMode(lockMode);
        Optional<InvitationEntity> invitation = statement.getResultStream().findFirst();
        invitation.ifPresent(entity -> entityManager.refresh(entity, lockMode));
        return invitation;
    }

}
