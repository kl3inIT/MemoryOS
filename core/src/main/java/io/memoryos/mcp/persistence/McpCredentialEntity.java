package io.memoryos.mcp.persistence;

import io.memoryos.mcp.McpCredentialStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * A sealed credential for one MCP server: a User's own connection, or the shared administrator credential
 * when {@code ownerActorId} is null. The payload never leaves the MCP capability unsealed.
 */
@Entity
@Table(name = "mcp_credential")
public class McpCredentialEntity implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "server_id", nullable = false, updatable = false) private UUID serverId;
    @Column(name = "owner_actor_id", updatable = false) private @Nullable UUID ownerActorId;
    @Column(name = "oauth_client_id") private @Nullable UUID oauthClientId;
    @Column(nullable = false, columnDefinition = "text") private String payload;
    @Column(name = "access_expires_at") private @Nullable Instant accessExpiresAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private McpCredentialStatus status = McpCredentialStatus.ACTIVE;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long revision = 1;
    @Transient private boolean fresh = true;

    protected McpCredentialEntity() {}

    public McpCredentialEntity(UUID id, UUID tenantId, UUID serverId, @Nullable UUID ownerActorId, Instant now) {
        this.id = id; this.tenantId = tenantId; this.serverId = serverId; this.ownerActorId = ownerActorId;
        createdAt = now; updatedAt = now;
    }

    public void store(@Nullable UUID oauthClientId, String payload, @Nullable Instant accessExpiresAt, Instant now) {
        this.oauthClientId = oauthClientId; this.payload = payload; this.accessExpiresAt = accessExpiresAt;
        status = McpCredentialStatus.ACTIVE; updatedAt = now;
    }

    public void requireReauthorization(Instant now) {
        status = McpCredentialStatus.REAUTH_REQUIRED; updatedAt = now;
    }

    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist void persisted() { fresh = false; }
    public UUID tenantId() { return tenantId; }
    public UUID serverId() { return serverId; }
    public @Nullable UUID ownerActorId() { return ownerActorId; }
    public @Nullable UUID oauthClientId() { return oauthClientId; }
    public String payload() { return payload; }
    public @Nullable Instant accessExpiresAt() { return accessExpiresAt; }
    public McpCredentialStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long revision() { return revision; }
}
