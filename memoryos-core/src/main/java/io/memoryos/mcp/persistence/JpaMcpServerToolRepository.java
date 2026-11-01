package io.memoryos.mcp.persistence;

import io.memoryos.mcp.McpToolPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaMcpServerToolRepository extends JpaRepository<McpServerToolEntity, UUID> {
    List<McpServerToolEntity> findByTenantIdAndServerIdOrderByNameAsc(UUID tenantId, UUID serverId);
    Optional<McpServerToolEntity> findByTenantIdAndServerIdAndId(UUID tenantId, UUID serverId, UUID id);
    Optional<McpServerToolEntity> findByTenantIdAndServerIdAndName(UUID tenantId, UUID serverId, String name);
    long countByTenantIdAndServerId(UUID tenantId, UUID serverId);
    long countByTenantIdAndServerIdAndPolicyNot(UUID tenantId, UUID serverId, McpToolPolicy policy);
}
