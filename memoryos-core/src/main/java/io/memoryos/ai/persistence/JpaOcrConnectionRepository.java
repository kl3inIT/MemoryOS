package io.memoryos.ai.persistence;

import io.memoryos.ai.ocr.OcrProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOcrConnectionRepository extends JpaRepository<OcrConnectionEntity, UUID> {
    List<OcrConnectionEntity> findByTenantIdOrderByProvider(UUID tenantId);
    Optional<OcrConnectionEntity> findByTenantIdAndProvider(UUID tenantId, OcrProvider provider);
    Optional<OcrConnectionEntity> findByTenantIdAndActiveTrue(UUID tenantId);
}
