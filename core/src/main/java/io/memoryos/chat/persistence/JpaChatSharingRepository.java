package io.memoryos.chat.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaChatSharingRepository extends JpaRepository<ChatSharingEntity, UUID> {
    Optional<ChatSharingEntity> findByTenantIdAndSessionId(UUID tenantId, UUID sessionId);
}
