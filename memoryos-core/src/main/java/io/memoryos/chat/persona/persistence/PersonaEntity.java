package io.memoryos.chat.persona.persistence;

import io.memoryos.ai.ReasoningEffort;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    /** The role line under the name (MEM-273). */
    @Column(length = 80) private @Nullable String title;
    @Column(nullable = false, length = 2000) private String description = "";
    @Column(nullable = false, columnDefinition = "text") private String instructions = "";
    /** What an empty conversation with this agent opens with instead of the default heading (MEM-273). */
    @Column(length = 500) private @Nullable String greeting;
    /** The agent's own reasoning level; a level pinned on the conversation outranks it (MEM-273). */
    @Enumerated(EnumType.STRING) @Column(name = "reasoning_effort", length = 16) private @Nullable ReasoningEffort reasoningEffort;
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
    /** The seed of the agent's DiceBear face; renaming keeps it (MEM-273). */
    @Column(name = "avatar_seed", nullable = false, length = 64) private String avatarSeed = UUID.randomUUID().toString();
    @Column(name = "avatar_file_id") private @Nullable UUID avatarFileId;
    /** MEM-195: this agent answers from the organization's documents only, even when the Tenant setting is off. */
    @Column(name = "grounded", nullable = false) private boolean grounded;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "deleted_at") private @Nullable Instant deletedAt;
    @Version private @Nullable Long revision;

    protected PersonaEntity() {}
    public PersonaEntity(UUID id, UUID tenantId, @Nullable UUID ownerId) {
        this.id = id; this.tenantId = tenantId; this.ownerId = ownerId;
    }
    public record Settings(String name, @Nullable String title, String description, String instructions,
                           @Nullable String greeting, List<String> starters, List<UUID> sources, @Nullable UUID modelId,
                           @Nullable ReasoningEffort reasoningEffort, @Nullable String avatarSeed,
                           @Nullable UUID avatarFileId, boolean grounded) {}
    public void update(Settings settings) {
        this.name = settings.name(); this.title = settings.title(); this.description = settings.description();
        this.instructions = settings.instructions(); this.greeting = settings.greeting();
        this.starterPrompts.clear(); this.starterPrompts.addAll(settings.starters());
        this.sourceIds.clear(); this.sourceIds.addAll(settings.sources());
        if (!Objects.equals(this.modelConfigurationId, settings.modelId())) modelRevision++;
        this.modelConfigurationId = settings.modelId();
        this.reasoningEffort = settings.reasoningEffort();
        if (settings.avatarSeed() != null) this.avatarSeed = settings.avatarSeed();
        this.avatarFileId = settings.avatarFileId();
        this.grounded = settings.grounded();
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
    public @Nullable String title() { return title; }
    public String description() { return description; }
    public String instructions() { return instructions; }
    public @Nullable String greeting() { return greeting; }
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
    public String avatarSeed() { return avatarSeed; }
    public @Nullable UUID avatarFileId() { return avatarFileId; }
    public boolean grounded() { return grounded; }
    public @Nullable Instant deletedAt() { return deletedAt; }
    public @Nullable UUID modelConfigurationId() { return modelConfigurationId; }
    public @Nullable ReasoningEffort reasoningEffort() { return reasoningEffort; }
    public boolean deleted() { return deletedAt != null; }
    public long revision() { return revision == null ? 0 : revision; }
}
