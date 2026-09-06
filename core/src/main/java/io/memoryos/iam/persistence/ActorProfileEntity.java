package io.memoryos.iam.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "actor_profiles")
public class ActorProfileEntity {

    @Id
    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false, updatable = false)
    private ActorEntity actor;

    @Column(name = "issuer", nullable = false, columnDefinition = "text")
    private String issuer;

    @Column(name = "subject", nullable = false, columnDefinition = "text")
    private String subject;


    @Column(name = "display_name", columnDefinition = "text")
    private @Nullable String displayName;

    @Column(name = "email", columnDefinition = "text")
    private @Nullable String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    protected ActorProfileEntity() {
    }

    public ActorProfileEntity(
            ActorEntity actor,
            ExternalIdentityBindingEntity identityBinding,
            @Nullable String displayName,
            @Nullable String email,
            boolean emailVerified,
            Instant observedAt
    ) {
        this.actor = Objects.requireNonNull(actor, "actor must not be null");
        this.actorId = actor.getId();
        observe(identityBinding, displayName, email, emailVerified, observedAt);
    }

    public void observe(
            ExternalIdentityBindingEntity identityBinding,
            @Nullable String displayName,
            @Nullable String email,
            boolean emailVerified,
            Instant observedAt
    ) {
        ExternalIdentityBindingEntity binding = Objects.requireNonNull(identityBinding, "identityBinding must not be null");
        if (!binding.getActor().getId().equals(actor.getId())) {
            throw new IllegalArgumentException("identityBinding must belong to actor");
        }
        this.issuer = binding.getIssuer();
        this.subject = binding.getSubject();
        this.displayName = displayName;
        this.email = email;
        this.emailVerified = emailVerified;
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    public UUID getActorId() {
        return actorId;
    }

    public ActorEntity getActor() {
        return actor;
    }
    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }



    public @Nullable String getDisplayName() {
        return displayName;
    }

    public @Nullable String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

}
