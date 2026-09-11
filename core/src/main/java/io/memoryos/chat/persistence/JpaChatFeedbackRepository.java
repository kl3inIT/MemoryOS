package io.memoryos.chat.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaChatFeedbackRepository extends JpaRepository<ChatFeedbackEntity, UUID> {
    Optional<ChatFeedbackEntity> findByTenantIdAndActorIdAndAssistantId(UUID tenantId, UUID actorId, UUID assistantId);
    List<ChatFeedbackEntity> findByTenantIdAndActorIdAndSessionIdAndAssistantIdIn(UUID tenantId, UUID actorId, UUID sessionId, List<UUID> assistantIds);
}
