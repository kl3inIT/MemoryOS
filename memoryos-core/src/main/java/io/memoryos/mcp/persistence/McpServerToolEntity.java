package io.memoryos.mcp.persistence;

import io.memoryos.mcp.McpToolPolicy;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/** A tool from the latest administrator snapshot; Chat sends this schema, not a live {@code tools/list}. */
@Entity
@Table(name = "mcp_server_tool")
public class McpServerToolEntity implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "server_id", nullable = false, updatable = false) private UUID serverId;
    @Column(nullable = false, length = 128, updatable = false) private String name;
    @Column(length = 200) private @Nullable String title;
    @Column(nullable = false, columnDefinition = "text") private String description = "";
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_schema", nullable = false, columnDefinition = "jsonb") private String inputSchema;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String annotations = "{}";
    @Column(name = "read_only", nullable = false) private boolean readOnly;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private McpToolPolicy policy = McpToolPolicy.OFF;
    /** An administrator chose the policy, so a refresh keeps it instead of applying the default. */
    @Column(name = "policy_customized", nullable = false) private boolean policyCustomized;
    /** The tool's MCP Apps view (MEM-275). */
    @Column(name = "app_resource_uri", length = 1024) private @Nullable String appResourceUri;
    @Column(name = "model_visible", nullable = false) private boolean modelVisible = true;
    @Column(name = "app_visible", nullable = false) private boolean appVisible = true;
    @Column(name = "snapshot_at", nullable = false) private Instant snapshotAt;
    @Version private long revision = 1;
    @Transient private boolean fresh = true;

    protected McpServerToolEntity() {}

    public McpServerToolEntity(UUID id, UUID tenantId, UUID serverId, String name) {
        this.id = id; this.tenantId = tenantId; this.serverId = serverId; this.name = name;
    }

    public void snapshot(@Nullable String title, String description, String inputSchema, String annotations,
                         boolean readOnly, Instant at) {
        this.title = title; this.description = description; this.inputSchema = inputSchema;
        this.annotations = annotations; this.readOnly = readOnly; snapshotAt = at;
    }

    /** The tool's view and who may call it, from the same listing as {@link #snapshot}. */
    public void app(@Nullable String resourceUri, boolean modelVisible, boolean appVisible) {
        appResourceUri = resourceUri; this.modelVisible = modelVisible; this.appVisible = appVisible;
    }

    public void policy(McpToolPolicy policy, boolean customized) {
        this.policy = policy;
        policyCustomized = customized;
    }

    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist void persisted() { fresh = false; }
    public UUID tenantId() { return tenantId; }
    public UUID serverId() { return serverId; }
    public String name() { return name; }
    public @Nullable String title() { return title; }
    public String description() { return description; }
    public String inputSchema() { return inputSchema; }
    public String annotations() { return annotations; }
    public boolean readOnly() { return readOnly; }
    public McpToolPolicy policy() { return policy; }
    public boolean policyCustomized() { return policyCustomized; }
    public @Nullable String appResourceUri() { return appResourceUri; }
    public boolean modelVisible() { return modelVisible; }
    public boolean appVisible() { return appVisible; }
    public Instant snapshotAt() { return snapshotAt; }
    public long revision() { return revision; }
}
