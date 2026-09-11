package io.memoryos.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "model_configuration")
public class ModelConfigurationEntity implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "provider_id", nullable = false, updatable = false) private UUID providerId;
    @Column(name = "model_name", nullable = false, length = 200) private String modelName;
    @Column(name = "display_name", nullable = false, length = 200) private String displayName;
    @Column(nullable = false) private boolean visible;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String settings;
    @Version private long revision = 1;
    @Transient private boolean fresh = true;
    protected ModelConfigurationEntity() {}
    public ModelConfigurationEntity(UUID id, UUID tenant, UUID provider) { this.id = id; tenantId = tenant; providerId = provider; }
    public void update(String modelName, String displayName, boolean visible, String settings) {
        this.modelName = modelName; this.displayName = displayName; this.visible = visible; this.settings = settings;
    }
    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist void persisted() { fresh = false; }
    public UUID tenantId() { return tenantId; }
    public UUID providerId() { return providerId; }
    public String modelName() { return modelName; }
    public String displayName() { return displayName; }
    public boolean visible() { return visible; }
    public String settings() { return settings; }
    public long revision() { return revision; }
}
