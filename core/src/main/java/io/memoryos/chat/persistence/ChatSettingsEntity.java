package io.memoryos.chat.persistence;

import jakarta.persistence.*;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One row per Tenant once an administrator saves Chat settings; absent means the defaults. */
@Entity
@Table(name = "chat_settings")
public class ChatSettingsEntity {
    @Id @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "deep_research_enabled", nullable = false) private boolean deepResearchEnabled = true;
    /** Days of inactivity after which a conversation is deleted; null is no policy, which is the default. */
    @Column(name = "chat_retention_days") private @Nullable Integer chatRetentionDays;
    @Version private @Nullable Long revision;

    protected ChatSettingsEntity() {}
    public ChatSettingsEntity(UUID tenant) { tenantId = tenant; }
    public UUID tenantId() { return tenantId; }
    public boolean deepResearchEnabled() { return deepResearchEnabled; }
    public long revision() { return revision == null ? 0 : revision; }
    public void deepResearchEnabled(boolean enabled) { deepResearchEnabled = enabled; }
    public @Nullable Integer chatRetentionDays() { return chatRetentionDays; }
    public void chatRetentionDays(@Nullable Integer days) { chatRetentionDays = days; }
}
