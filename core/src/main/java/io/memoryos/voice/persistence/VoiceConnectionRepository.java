package io.memoryos.voice.persistence;

import io.memoryos.voice.VoiceProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceConnectionRepository extends JpaRepository<VoiceConnectionEntity, UUID> {
    List<VoiceConnectionEntity> findByTenantIdOrderByProvider(UUID tenantId);
    Optional<VoiceConnectionEntity> findByTenantIdAndProvider(UUID tenantId, VoiceProvider provider);
}
