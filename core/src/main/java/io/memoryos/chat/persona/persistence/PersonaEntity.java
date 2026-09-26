package io.memoryos.chat.persona.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "persona")
public class PersonaEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "owner_actor_id") private @Nullable UUID ownerId;
    @Column(name = "owner_group_id") private @Nullable UUID ownerGroupId;
    @Column(name = "builtin_key", length = 32, updatable = false) private @Nullable String builtinKey;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, length = 2000) private String description = "";
    @Column(nullable = false, columnDefinition = "text") private String instructions = "";
    @Column(nullable = false, length = 200) private String model;
    @Column(name = "model_configuration_id") private @Nullable UUID modelConfigurationId;
    @Column(name = "model_revision", nullable = false) private long modelRevision = 1;
    @ElementCollection @CollectionTable(name = "persona_starter", joinColumns = @JoinColumn(name = "persona_id"))
    @OrderColumn(name = "position") @Column(name = "prompt", nullable = false, length = 1000)
    private List<String> starterPrompts = new ArrayList<>();
    @ElementCollection @CollectionTable(name = "persona_source", joinColumns = @JoinColumn(name = "persona_id"))
    @Column(name = "source_id", nullable = false)
    private List<UUID> sourceIds = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "file_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> fileIds = new ArrayList<>();
    @Column(name = "is_public", nullable = false) private boolean isPublic;
    @Column(name = "public_permission", nullable = false, length = 8) private String publicPermission = "VIEWER";
    @Column(name = "is_listed", nullable = false) private boolean listed = true;
    @Column(name = "is_featured", nullable = false) private boolean featured;
    @Column(name = "display_priority") private @Nullable Integer displayPriority;
    @Column(name = "icon_name", length = 40) private @Nullable String iconName;
    @Column(name = "avatar_file_id") private @Nullable UUID avatarFileId;
    @Column(name = "task_prompt", nullable = false, columnDefinition = "text") private String taskPrompt = "";
    @Column(name = "replace_base_system_prompt", nullable = false) private boolean replaceBaseSystemPrompt;
    @Column(name = "knowledge_cutoff") private @Nullable Instant knowledgeCutoff;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "context_token_limit") private @Nullable Integer contextTokenLimit;
    @Column(name = "output_token_limit") private @Nullable Integer outputTokenLimit;
    @Column(name = "deleted_at") private @Nullable Instant deletedAt;
    @Version private @Nullable Long revision;

    protected PersonaEntity() {}
    public PersonaEntity(UUID id, UUID tenantId, @Nullable UUID ownerId, String model) {
        this.id = id; this.tenantId = tenantId; this.ownerId = ownerId; this.model = model;
    }
    public record Settings(String name, String description, String instructions, String taskPrompt, List<String> starters,
                           List<UUID> sources, @Nullable UUID modelId, @Nullable Integer contextLimit, @Nullable Integer outputLimit,
                           @Nullable String iconName, @Nullable UUID avatarFileId, boolean replaceBaseSystemPrompt,
                           @Nullable Instant knowledgeCutoff) {}
    public void update(Settings settings) {
        this.name = settings.name(); this.description = settings.description(); this.instructions = settings.instructions();
        this.taskPrompt = settings.taskPrompt();
        this.starterPrompts.clear(); this.starterPrompts.addAll(settings.starters());
        this.sourceIds.clear(); this.sourceIds.addAll(settings.sources());
        if (!Objects.equals(this.modelConfigurationId, settings.modelId())) modelRevision++;
        this.modelConfigurationId = settings.modelId();
        this.contextTokenLimit = settings.contextLimit(); this.outputTokenLimit = settings.outputLimit();
        this.iconName = settings.iconName(); this.avatarFileId = settings.avatarFileId();
        this.replaceBaseSystemPrompt = settings.replaceBaseSystemPrompt();
        this.knowledgeCutoff = settings.knowledgeCutoff();
    }
    public void publish(boolean isPublic, String permission) { this.isPublic = isPublic; this.publicPermission = permission; }
    public void listing(boolean listed, boolean featured, @Nullable Integer displayPriority) {
        this.listed = listed; this.featured = featured; this.displayPriority = displayPriority;
    }
    public void transfer(@Nullable UUID actor, @Nullable UUID group) { this.ownerId = actor; this.ownerGroupId = group; }
    public void delete() { deletedAt = Instant.now(); }
    public void restore() { deletedAt = null; }
    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public @Nullable UUID ownerId() { return ownerId; }
    public boolean builtin() { return builtinKey != null; }
    public String name() { return name; }
    public String description() { return description; }
    public String instructions() { return instructions; }
    public List<String> starterPrompts() { return List.copyOf(starterPrompts); }
    public List<UUID> sourceIds() { return List.copyOf(sourceIds); }
    public List<UUID> fileIds() { return List.copyOf(fileIds); }
    public void files(List<UUID> ids) { fileIds = new ArrayList<>(ids); }
    public @Nullable UUID ownerGroupId() { return ownerGroupId; }
    public boolean isPublic() { return isPublic; }
    public String publicPermission() { return publicPermission; }
    public boolean listed() { return listed; }
    public boolean featured() { return featured; }
    public @Nullable Integer displayPriority() { return displayPriority; }
    public @Nullable String iconName() { return iconName; }
    public @Nullable UUID avatarFileId() { return avatarFileId; }
    public String taskPrompt() { return taskPrompt; }
    public boolean replaceBaseSystemPrompt() { return replaceBaseSystemPrompt; }
    public @Nullable Instant knowledgeCutoff() { return knowledgeCutoff; }
    public @Nullable Instant deletedAt() { return deletedAt; }
    public @Nullable UUID modelConfigurationId() { return modelConfigurationId; }
    public @Nullable Integer contextTokenLimit() { return contextTokenLimit; }
    public @Nullable Integer outputTokenLimit() { return outputTokenLimit; }
    public boolean deleted() { return deletedAt != null; }
    public long revision() { return revision == null ? 0 : revision; }
}
