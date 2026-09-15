package io.memoryos.mcp.persistence;

import io.memoryos.mcp.McpAuthPerformer;
import io.memoryos.mcp.McpAuthType;
import io.memoryos.mcp.McpOAuthProviderMode;
import io.memoryos.mcp.McpServerStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "mcp_server")
public class McpServerEntity implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(nullable = false, length = 16, updatable = false) private String slug;
    @Column(nullable = false, length = 200) private String name;
    @Column(length = 2000) private @Nullable String description;
    @Column(nullable = false, length = 2048) private String url;
    @Enumerated(EnumType.STRING) @Column(name = "auth_type", nullable = false, length = 16) private McpAuthType authType;
    @Enumerated(EnumType.STRING) @Column(name = "auth_performer", nullable = false, length = 16) private McpAuthPerformer authPerformer;
    @Enumerated(EnumType.STRING) @Column(name = "oauth_provider_mode", length = 16) private @Nullable McpOAuthProviderMode oauthProviderMode;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "oauth_scopes", nullable = false, columnDefinition = "jsonb") private String oauthScopes = "[]";
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "oauth_additional_parameters", nullable = false, columnDefinition = "jsonb")
    private String oauthAdditionalParameters = "{}";
    @Column(name = "header_template", columnDefinition = "text") private @Nullable String headerTemplate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private McpServerStatus status = McpServerStatus.CREATED;
    @Column(name = "tenant_wide", nullable = false) private boolean tenantWide = true;
    @Column(name = "last_refreshed_at") private @Nullable Instant lastRefreshedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @ElementCollection @BatchSize(size = 64)
    @CollectionTable(name = "mcp_server_group", joinColumns = {
            @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id"), @JoinColumn(name = "server_id", referencedColumnName = "id")})
    @Column(name = "group_id", nullable = false) private Set<UUID> groupIds = new HashSet<>();
    @Version private long revision = 1;
    @Transient private boolean fresh = true;

    protected McpServerEntity() {}

    public McpServerEntity(UUID id, UUID tenantId, String slug, Instant now) {
        this.id = id; this.tenantId = tenantId; this.slug = slug; createdAt = now; updatedAt = now;
    }

    public void configure(String name, @Nullable String description, String url, McpAuthType authType,
                          McpAuthPerformer authPerformer, @Nullable McpOAuthProviderMode oauthProviderMode,
                          String oauthScopes, String oauthAdditionalParameters, @Nullable String headerTemplate,
                          boolean tenantWide, Set<UUID> groups, Instant now) {
        this.name = name; this.description = description; this.url = url; this.authType = authType;
        this.authPerformer = authPerformer; this.oauthProviderMode = oauthProviderMode; this.oauthScopes = oauthScopes;
        this.oauthAdditionalParameters = oauthAdditionalParameters; this.headerTemplate = headerTemplate;
        this.tenantWide = tenantWide; groupIds.clear(); groupIds.addAll(groups); updatedAt = now;
    }

    public void status(McpServerStatus status, @Nullable Instant refreshedAt, Instant now) {
        this.status = status;
        if (refreshedAt != null) lastRefreshedAt = refreshedAt;
        updatedAt = now;
    }

    /** A changed endpoint invalidates the previous tool snapshot and its refresh time. */
    public void reconnect(McpServerStatus status, Instant now) {
        this.status = status; lastRefreshedAt = null; updatedAt = now;
    }

    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist void persisted() { fresh = false; }
    public UUID tenantId() { return tenantId; }
    public String slug() { return slug; }
    public String name() { return name; }
    public @Nullable String description() { return description; }
    public String url() { return url; }
    public McpAuthType authType() { return authType; }
    public McpAuthPerformer authPerformer() { return authPerformer; }
    public @Nullable McpOAuthProviderMode oauthProviderMode() { return oauthProviderMode; }
    public String oauthScopes() { return oauthScopes; }
    public String oauthAdditionalParameters() { return oauthAdditionalParameters; }
    public @Nullable String headerTemplate() { return headerTemplate; }
    public McpServerStatus status() { return status; }
    public boolean tenantWide() { return tenantWide; }
    public @Nullable Instant lastRefreshedAt() { return lastRefreshedAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Set<UUID> groupIds() { return Set.copyOf(groupIds); }
    public long revision() { return revision; }
}
