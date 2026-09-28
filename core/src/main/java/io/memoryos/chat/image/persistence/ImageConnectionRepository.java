package io.memoryos.chat.image.persistence;

import io.memoryos.chat.image.ImageProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageConnectionRepository extends JpaRepository<ImageConnectionEntity, UUID> {
    List<ImageConnectionEntity> findByTenantIdOrderByProvider(UUID tenantId);
    Optional<ImageConnectionEntity> findByTenantIdAndProvider(UUID tenantId, ImageProvider provider);
}
