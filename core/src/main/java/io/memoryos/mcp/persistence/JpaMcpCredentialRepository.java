package io.memoryos.mcp.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaMcpCredentialRepository extends JpaRepository<McpCredentialEntity, UUID> {
    Optional<McpCredentialEntity> findByTenantIdAndServerIdAndOwnerActorId(UUID tenantId, UUID serverId, UUID ownerActorId);
    Optional<McpCredentialEntity> findByTenantIdAndServerIdAndOwnerActorIdIsNull(UUID tenantId, UUID serverId);
    List<McpCredentialEntity> findByTenantIdAndServerId(UUID tenantId, UUID serverId);
}
