package io.memoryos.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_feedback")
public class ChatFeedbackEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "actor_id", nullable = false, updatable = false) private UUID actorId;
    @Column(name = "session_id", nullable = false, updatable = false) private UUID sessionId;
    @Column(name = "assistant_message_id", nullable = false, updatable = false) private UUID assistantId;
    private @Nullable Boolean positive;
    @Column(nullable = false, length = 4000) private String comment = "";
    @Column(nullable = false, length = 100) private String reason = "";
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private @Nullable Long revision;
    protected ChatFeedbackEntity() {}
    public ChatFeedbackEntity(UUID tenant, UUID actor, UUID session, UUID assistant) {
        id = UUID.randomUUID(); tenantId = tenant; actorId = actor; sessionId = session; assistantId = assistant;
    }
    public void update(@Nullable Boolean positive, String comment, String reason) {
        this.positive = positive; this.comment = comment; this.reason = reason; updatedAt = Instant.now();
    }
    public UUID assistantId() { return assistantId; }
    public @Nullable Boolean positive() { return positive; }
    public String comment() { return comment; }
    public String reason() { return reason; }
}
