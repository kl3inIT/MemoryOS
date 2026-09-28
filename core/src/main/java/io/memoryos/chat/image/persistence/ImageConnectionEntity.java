package io.memoryos.chat.image.persistence;

import io.memoryos.chat.image.ImageProvider;
import jakarta.persistence.*;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "chat_image_connection")
public class ImageConnectionEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) private ImageProvider provider;
    @Column(nullable = false, length = 2048) private String endpoint = "";
    @Column(nullable = false, length = 200) private String model = "";
    @Column(columnDefinition = "text") private @Nullable String credential;
    @Column(nullable = false) private boolean active;
    @Version private @Nullable Long revision;

    protected ImageConnectionEntity() {}
    public ImageConnectionEntity(UUID tenant, ImageProvider provider) {
        id = UUID.randomUUID(); tenantId = tenant; this.provider = provider;
    }
    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public ImageProvider provider() { return provider; }
    public String endpoint() { return endpoint; }
    public String model() { return model; }
    public @Nullable String credential() { return credential; }
    public boolean active() { return active; }
    public long revision() { return revision == null ? 0 : revision; }
    public void configure(String endpoint, String model, @Nullable String credential) {
        this.endpoint = endpoint; this.model = model; this.credential = credential;
    }
    public void select(boolean active) { this.active = active; }
}
