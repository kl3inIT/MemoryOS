package io.memoryos.mcp.persistence;

import io.memoryos.mcp.McpOAuthClientSource;
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

/** One OAuth client of an MCP server; secrets are stored sealed by {@code McpSecrets}. */
@Entity
@Table(name = "mcp_oauth_client")
public class McpOAuthClientEntity implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "server_id", nullable = false, updatable = false) private UUID serverId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24, updatable = false) private McpOAuthClientSource source;
    @Column(nullable = false, length = 100) private String label;
    @Column(nullable = false, length = 2048) private String issuer;
    @Column(name = "client_id", nullable = false, length = 2048) private String clientId;
    @Column(name = "client_secret", columnDefinition = "text") private @Nullable String clientSecret;
    @Column(name = "authorization_endpoint", nullable = false, length = 2048) private String authorizationEndpoint;
    @Column(name = "token_endpoint", nullable = false, length = 2048) private String tokenEndpoint;
    @Column(name = "revocation_endpoint", length = 2048) private @Nullable String revocationEndpoint;
    @Column(name = "registration_client_uri", length = 2048) private @Nullable String registrationClientUri;
    @Column(name = "registration_access_token", columnDefinition = "text") private @Nullable String registrationAccessToken;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long revision = 1;
    @Transient private boolean fresh = true;

    protected McpOAuthClientEntity() {}

    public McpOAuthClientEntity(UUID id, UUID tenantId, UUID serverId, McpOAuthClientSource source, Instant now) {
        this.id = id; this.tenantId = tenantId; this.serverId = serverId; this.source = source;
        createdAt = now; updatedAt = now;
    }

    public void configure(String label, String issuer, String clientId, @Nullable String clientSecret,
                          String authorizationEndpoint, String tokenEndpoint, @Nullable String revocationEndpoint,
                          @Nullable String registrationClientUri, @Nullable String registrationAccessToken, Instant now) {
        this.label = label; this.issuer = issuer; this.clientId = clientId; this.clientSecret = clientSecret;
        this.authorizationEndpoint = authorizationEndpoint; this.tokenEndpoint = tokenEndpoint;
        this.revocationEndpoint = revocationEndpoint; this.registrationClientUri = registrationClientUri;
        this.registrationAccessToken = registrationAccessToken; updatedAt = now;
    }

    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist void persisted() { fresh = false; }
    public UUID tenantId() { return tenantId; }
    public UUID serverId() { return serverId; }
    public McpOAuthClientSource source() { return source; }
    public String label() { return label; }
    public String issuer() { return issuer; }
    public String clientId() { return clientId; }
    public @Nullable String clientSecret() { return clientSecret; }
    public String authorizationEndpoint() { return authorizationEndpoint; }
    public String tokenEndpoint() { return tokenEndpoint; }
    public @Nullable String revocationEndpoint() { return revocationEndpoint; }
    public @Nullable String registrationClientUri() { return registrationClientUri; }
    public @Nullable String registrationAccessToken() { return registrationAccessToken; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long revision() { return revision; }
}
