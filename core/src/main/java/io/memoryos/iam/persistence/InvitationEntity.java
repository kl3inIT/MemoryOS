package io.memoryos.iam.persistence;

import io.memoryos.iam.InvitationException;
import io.memoryos.iam.InvitationFailureReason;
import io.memoryos.iam.InvitationStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "tenant_invitations")
public class InvitationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private TenantEntity tenant;

    @Column(name = "normalized_email", nullable = false, length = 254, updatable = false)
    private String normalizedEmail;

    @Column(name = "open_email_key", length = 254)
    private @Nullable String openEmailKey;

    @Column(name = "secret_digest", nullable = false, length = 64)
    private String secretDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InvitationStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_actor_id", nullable = false, updatable = false)
    private ActorEntity createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by_actor_id")
    private @Nullable ActorEntity acceptedBy;

    @Column(name = "accepted_at")
    private @Nullable Instant acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by_actor_id")
    private @Nullable ActorEntity revokedBy;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    protected InvitationEntity() {
    }

    public InvitationEntity(
            UUID id,
            TenantEntity tenant,
            String normalizedEmail,
            String secretDigest,
            ActorEntity createdBy,
            Instant createdAt,
            Instant expiresAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        this.normalizedEmail = requireText(normalizedEmail, "normalizedEmail");
        this.openEmailKey = this.normalizedEmail;
        this.secretDigest = requireText(secretDigest, "secretDigest");
        this.status = InvitationStatus.PENDING;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = createdAt;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    public boolean isAvailableAt(Instant now) {
        return status == InvitationStatus.PENDING && expiresAt.isAfter(now);
    }

    public boolean settleExpiry(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != InvitationStatus.PENDING || expiresAt.isAfter(now)) {
            return false;
        }
        status = InvitationStatus.EXPIRED;
        openEmailKey = null;
        updatedAt = now;
        return true;
    }

    public void rotate(String secretDigest, Instant now, Instant expiresAt) {
        requirePending(now);
        this.secretDigest = requireText(secretDigest, "secretDigest");
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be after now");
        }
    }

    public void accept(ActorEntity actor, Instant now) {
        requirePending(now);
        status = InvitationStatus.ACCEPTED;
        openEmailKey = null;
        acceptedBy = Objects.requireNonNull(actor, "actor must not be null");
        acceptedAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
    }

    public void revoke(ActorEntity actor, Instant now) {
        requirePending(now);
        status = InvitationStatus.REVOKED;
        openEmailKey = null;
        revokedBy = Objects.requireNonNull(actor, "actor must not be null");
        revokedAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
    }

    private void requirePending(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (settleExpiry(now) || status != InvitationStatus.PENDING) {
            throw new InvitationException(
                    InvitationFailureReason.NOT_AVAILABLE,
                    "invitation is not available"
            );
        }
    }

    public UUID getId() {
        return id;
    }

    public TenantEntity getTenant() {
        return tenant;
    }

    public String getNormalizedEmail() {
        return normalizedEmail;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public @Nullable ActorEntity getAcceptedBy() {
        return acceptedBy;
    }

    public @Nullable Instant getAcceptedAt() {
        return acceptedAt;
    }

    public @Nullable Instant getRevokedAt() {
        return revokedAt;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
