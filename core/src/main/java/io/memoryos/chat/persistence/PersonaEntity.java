package io.memoryos.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
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
    @Column(name = "owner_actor_id", updatable = false) private @Nullable UUID ownerId;
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
    @Column(name = "search_enabled", nullable = false) private boolean searchEnabled = true;
    @Column(name = "context_token_limit") private @Nullable Integer contextTokenLimit;
    @Column(name = "output_token_limit") private @Nullable Integer outputTokenLimit;
    @Column(name = "deleted_at") private @Nullable Instant deletedAt;
    @Version private @Nullable Long revision;

    protected PersonaEntity() {}
    public PersonaEntity(UUID id, UUID tenantId, UUID ownerId, String model) {
        this.id = id; this.tenantId = tenantId; this.ownerId = ownerId; this.model = model;
    }
    public void update(String name, String description, String instructions, List<String> starters,
                       List<UUID> sources, boolean search, @Nullable UUID modelId,
                       @Nullable Integer contextLimit, @Nullable Integer outputLimit) {
        this.name = name; this.description = description; this.instructions = instructions;
        this.starterPrompts.clear(); this.starterPrompts.addAll(starters);
        this.sourceIds.clear(); this.sourceIds.addAll(sources);
        this.searchEnabled = search; this.modelConfigurationId = modelId;
        this.contextTokenLimit = contextLimit; this.outputTokenLimit = outputLimit; modelRevision++;
    }
    public void delete() { deletedAt = Instant.now(); }
    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public @Nullable UUID ownerId() { return ownerId; }
    public boolean builtin() { return builtinKey != null; }
    public String name() { return name; }
    public String description() { return description; }
    public String instructions() { return instructions; }
    public List<String> starterPrompts() { return List.copyOf(starterPrompts); }
    public List<UUID> sourceIds() { return List.copyOf(sourceIds); }
    public boolean searchEnabled() { return searchEnabled; }
    public @Nullable UUID modelConfigurationId() { return modelConfigurationId; }
    public @Nullable Integer contextTokenLimit() { return contextTokenLimit; }
    public @Nullable Integer outputTokenLimit() { return outputTokenLimit; }
    public boolean deleted() { return deletedAt != null; }
    public long revision() { return revision == null ? 0 : revision; }
}
