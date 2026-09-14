package io.memoryos.chat.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaLlmProviderRepository extends JpaRepository<LlmProviderEntity, UUID> {
    List<LlmProviderEntity> findByTenantIdOrderByNameAscIdAsc(UUID tenantId, Pageable page);
    Optional<LlmProviderEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
