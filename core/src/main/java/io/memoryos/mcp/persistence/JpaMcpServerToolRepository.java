package io.memoryos.mcp.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaMcpServerToolRepository extends JpaRepository<McpServerToolEntity, UUID> {
    List<McpServerToolEntity> findByTenantIdAndServerIdOrderByNameAsc(UUID tenantId, UUID serverId);
}
