package io.memoryos.chat.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaModelConfigurationRepository extends JpaRepository<ModelConfigurationEntity, UUID> {
    List<ModelConfigurationEntity> findByTenantIdOrderByDisplayNameAscIdAsc(UUID tenantId, Pageable page);
    Optional<ModelConfigurationEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
