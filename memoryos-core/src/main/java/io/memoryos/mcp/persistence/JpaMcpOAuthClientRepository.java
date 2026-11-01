package io.memoryos.mcp.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaMcpOAuthClientRepository extends JpaRepository<McpOAuthClientEntity, UUID> {
    Optional<McpOAuthClientEntity> findByTenantIdAndServerIdAndId(UUID tenantId, UUID serverId, UUID id);
    List<McpOAuthClientEntity> findByTenantIdAndServerIdOrderByLabelAsc(UUID tenantId, UUID serverId);
    long countByTenantIdAndServerId(UUID tenantId, UUID serverId);
}
