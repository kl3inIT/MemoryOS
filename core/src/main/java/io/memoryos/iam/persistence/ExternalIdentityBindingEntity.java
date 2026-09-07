package io.memoryos.iam.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "external_identity_bindings")
public class ExternalIdentityBindingEntity {

    @EmbeddedId
    private ExternalIdentityBindingId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false, updatable = false)
    private ActorEntity actor;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ExternalIdentityBindingEntity() {
    }

    public ExternalIdentityBindingEntity(
            ExternalIdentityBindingId id,
            ActorEntity actor
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.actor = Objects.requireNonNull(actor, "actor must not be null");
    }

    public ExternalIdentityBindingId getId() {
        return id;
    }

    public String getIssuer() {
        return id.issuer();
    }

    public String getSubject() {
        return id.subject();
    }

    public ActorEntity getActor() {
        return actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
