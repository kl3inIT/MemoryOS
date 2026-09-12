package io.memoryos.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "chat_project")
public class ProjectEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "owner_actor_id", nullable = false, updatable = false) private UUID ownerId;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, length = 2000) private String description;
    @Column(nullable = false, columnDefinition = "text") private String instructions;
    @Version private @Nullable Long revision;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "file_ids", nullable = false, columnDefinition = "jsonb")
    private java.util.List<UUID> fileIds = new java.util.ArrayList<>();
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ProjectEntity() {}
    public ProjectEntity(UUID id, UUID tenant, UUID owner, String name, String description, String instructions) {
        this.id = id; this.tenantId = tenant; this.ownerId = owner; this.createdAt = Instant.now();
        update(name, description, instructions);
    }
    public void update(String name, String description, String instructions) {
        this.name = name; this.description = description; this.instructions = instructions; updatedAt = Instant.now();
    }
    public UUID id() { return id; }
    public java.util.List<UUID> fileIds() { return java.util.List.copyOf(fileIds); }
    public void files(java.util.List<UUID> ids) { fileIds = new java.util.ArrayList<>(ids); }
    public UUID tenantId() { return tenantId; }
    public String name() { return name; }
    public String description() { return description; }
    public String instructions() { return instructions; }
    public long revision() { return revision == null ? 0 : revision; }
    public Instant updatedAt() { return updatedAt; }
}
