package io.memoryos.chat.web.persistence;

import io.memoryos.chat.web.WebProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebConnectionRepository extends JpaRepository<WebConnectionEntity, UUID> {
    List<WebConnectionEntity> findByTenantIdOrderByProvider(UUID tenantId);
    Optional<WebConnectionEntity> findByTenantIdAndProvider(UUID tenantId, WebProvider provider);
}
