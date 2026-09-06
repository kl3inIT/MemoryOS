package io.memoryos.iam.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.memoryos.iam.AccountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "actors")
public class ActorEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ActorEntity() {
    }

    public ActorEntity(UUID id) {
        this(id, AccountType.STANDARD);
    }

    public ActorEntity(UUID id, AccountType accountType) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.accountType = Objects.requireNonNull(accountType, "accountType must not be null");
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
