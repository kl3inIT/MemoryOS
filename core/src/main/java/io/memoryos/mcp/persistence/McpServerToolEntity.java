package io.memoryos.mcp.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    @Column(nullable = false) private boolean enabled;
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

    public void enabled(boolean enabled) { this.enabled = enabled; }

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
    public boolean enabled() { return enabled; }
    public Instant snapshotAt() { return snapshotAt; }
    public long revision() { return revision; }
}
