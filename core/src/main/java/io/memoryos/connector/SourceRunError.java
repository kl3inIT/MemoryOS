package io.memoryos.connector;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record SourceRunError(
        UUID id, UUID runId, @Nullable UUID operationId, @Nullable UUID itemId,
        @Nullable String fileId, @Nullable String fileName, SourceRunErrorStage stage,
        String code, Instant occurredAt
) {}
