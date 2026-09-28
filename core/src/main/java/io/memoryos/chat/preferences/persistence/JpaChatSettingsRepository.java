package io.memoryos.chat.preferences.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaChatSettingsRepository extends JpaRepository<ChatSettingsEntity, UUID> {
}
