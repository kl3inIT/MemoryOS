package io.memoryos.chat.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaLlmProviderRepository extends JpaRepository<LlmProviderEntity, UUID> {
    Optional<LlmProviderEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
