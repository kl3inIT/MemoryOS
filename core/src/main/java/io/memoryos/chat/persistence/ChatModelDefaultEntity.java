package io.memoryos.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Created by the atomic deployment seed; subsequent lifecycle changes use JPA. */
@Entity
@Table(name = "chat_model_default")
public class ChatModelDefaultEntity {
    @Id @Column(name = "tenant_id") private UUID tenantId;
    @Column(name = "model_configuration_id") private @Nullable UUID modelId;
    @Version private long revision;
    protected ChatModelDefaultEntity() {}
    public void select(UUID model) { modelId = model; }
    public @Nullable UUID modelId() { return modelId; }
    public long revision() { return revision; }
}
