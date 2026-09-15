package io.memoryos.mcp.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaMcpServerRepository extends JpaRepository<McpServerEntity, UUID> {
    Optional<McpServerEntity> findByTenantIdAndId(UUID tenantId, UUID id);
    List<McpServerEntity> findByTenantIdOrderByNameAsc(UUID tenantId);
    boolean existsByTenantIdAndSlug(UUID tenantId, String slug);
}
