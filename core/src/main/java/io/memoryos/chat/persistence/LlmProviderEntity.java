package io.memoryos.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.JoinColumn;
import org.hibernate.annotations.BatchSize;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "llm_provider")
public class LlmProviderEntity implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "builtin_key", length = 32, updatable = false) private @Nullable String builtinKey;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "adapter_type", nullable = false, length = 64, updatable = false) private String adapterType;
    @Column(name = "base_url", nullable = false, length = 2048) private String baseUrl;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "is_public", nullable = false) private boolean publicAccess;
    @Column(columnDefinition = "text") private @Nullable String credential;
    @ElementCollection @BatchSize(size = 64)
    @CollectionTable(name = "llm_provider_group", joinColumns = {
            @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id"), @JoinColumn(name = "provider_id", referencedColumnName = "id")})
    @Column(name = "group_id", nullable = false) private Set<UUID> groupIds = new HashSet<>();
    @ElementCollection @BatchSize(size = 64)
    @CollectionTable(name = "llm_provider_persona", joinColumns = {
            @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id"), @JoinColumn(name = "provider_id", referencedColumnName = "id")})
    @Column(name = "persona_id", nullable = false) private Set<UUID> personaIds = new HashSet<>();
    @Version private long revision = 1;
    @Transient private boolean fresh = true;
    protected LlmProviderEntity() {}
    public LlmProviderEntity(UUID id, UUID tenant, @Nullable String builtinKey, String adapterType) {
        this.id = id; this.tenantId = tenant; this.builtinKey = builtinKey; this.adapterType = adapterType;
    }
    public void update(String name, String baseUrl, boolean enabled, boolean publicAccess, @Nullable String credential, Set<UUID> groups, Set<UUID> personas) {
        this.name = name; this.baseUrl = baseUrl; this.enabled = enabled; this.publicAccess = publicAccess; this.credential = credential;
        groupIds.clear(); groupIds.addAll(groups); personaIds.clear(); personaIds.addAll(personas);
    }
    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist void persisted() { fresh = false; }
    public UUID tenantId() { return tenantId; }
    public String name() { return name; }
    public String adapterType() { return adapterType; }
    public String baseUrl() { return baseUrl; }
    public boolean enabled() { return enabled; }
    public boolean publicAccess() { return publicAccess; }
    public @Nullable String credential() { return credential; }
    public long revision() { return revision; }
    public Set<UUID> groupIds() { return Set.copyOf(groupIds); }
    public Set<UUID> personaIds() { return Set.copyOf(personaIds); }
}
