package io.memoryos.ai.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaChatModelDefaultRepository extends JpaRepository<ChatModelDefaultEntity, UUID> {}
