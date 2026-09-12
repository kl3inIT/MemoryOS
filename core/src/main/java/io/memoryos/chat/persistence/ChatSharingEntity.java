package io.memoryos.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "chat_sharing")
public class ChatSharingEntity {
    @Id @Column(name = "session_id") private UUID sessionId;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(nullable = false) private boolean enabled;
    @Version private @org.jspecify.annotations.Nullable Long revision;
    protected ChatSharingEntity() {}
    public ChatSharingEntity(UUID tenantId, UUID sessionId) { this.tenantId = tenantId; this.sessionId = sessionId; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean enabled() { return enabled; }
    public long revision() { return revision == null ? 0 : revision; }
}
