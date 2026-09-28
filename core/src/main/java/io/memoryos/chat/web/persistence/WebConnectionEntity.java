package io.memoryos.chat.web.persistence;

import io.memoryos.chat.web.WebProvider;
import jakarta.persistence.*;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "chat_web_connection")
public class WebConnectionEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) private WebProvider provider;
    @Column(nullable = false, length = 2048) private String endpoint = "";
    @Column(name = "engine_id", nullable = false, length = 200) private String engineId = "";
    @Column(columnDefinition = "text") private @Nullable String credential;
    @Column(name = "search_active", nullable = false) private boolean searchActive;
    @Column(name = "content_active", nullable = false) private boolean contentActive;
    @Version private @Nullable Long revision;

    protected WebConnectionEntity() {}
    public WebConnectionEntity(UUID tenant, WebProvider provider) {
        id = UUID.randomUUID(); tenantId = tenant; this.provider = provider;
    }
    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public WebProvider provider() { return provider; }
    public String endpoint() { return endpoint; }
    public String engineId() { return engineId; }
    public @Nullable String credential() { return credential; }
    public boolean searchActive() { return searchActive; }
    public boolean contentActive() { return contentActive; }
    public long revision() { return revision == null ? 0 : revision; }
    public void configure(String endpoint, String engineId, @Nullable String credential) {
        this.endpoint = endpoint; this.engineId = engineId; this.credential = credential;
    }
    public void selectSearch(boolean active) { searchActive = active; }
    public void selectContent(boolean active) { contentActive = active; }
}
