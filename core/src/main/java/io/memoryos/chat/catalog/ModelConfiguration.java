package io.memoryos.chat.catalog;

import java.util.UUID;

public record ModelConfiguration(UUID id, UUID tenantId, UUID providerId, String modelName, String displayName,
                                 boolean visible, ModelSettings settings, long revision) {}
