package io.memoryos.chat.preferences.persistence;

import jakarta.persistence.*;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One row per Tenant once an administrator saves Chat settings; absent means the defaults. */
@Entity
@Table(name = "chat_settings")
public class ChatSettingsEntity {
    @Id @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "deep_research_enabled", nullable = false) private boolean deepResearchEnabled = true;
    /** MEM-125: how much of the Tenant's conversations an administrative reader may see. */
    @Column(name = "chat_history_visibility", nullable = false) private String chatHistoryVisibility = "NORMAL";
    @Version private @Nullable Long revision;

    protected ChatSettingsEntity() {}
    public ChatSettingsEntity(UUID tenant) { tenantId = tenant; }
    public UUID tenantId() { return tenantId; }
    public boolean deepResearchEnabled() { return deepResearchEnabled; }
    public long revision() { return revision == null ? 0 : revision; }
    public void deepResearchEnabled(boolean enabled) { deepResearchEnabled = enabled; }
    public String chatHistoryVisibility() { return chatHistoryVisibility; }
    public void chatHistoryVisibility(String visibility) { chatHistoryVisibility = visibility; }
}
