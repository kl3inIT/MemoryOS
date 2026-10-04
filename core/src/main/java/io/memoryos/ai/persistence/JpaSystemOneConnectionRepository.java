package io.memoryos.ai.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaSystemOneConnectionRepository extends JpaRepository<SystemOneConnectionEntity, UUID> {
    List<SystemOneConnectionEntity> findByTenantIdOrderByNameAscIdAsc(UUID tenantId);
    Optional<SystemOneConnectionEntity> findByTenantIdAndId(UUID tenantId, UUID id);
    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);
    long countByTenantId(UUID tenantId);
}
