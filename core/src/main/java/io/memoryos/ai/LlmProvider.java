package io.memoryos.ai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record LlmProvider(UUID id, UUID tenantId, String name, String adapterType, String baseUrl,
                          boolean enabled, boolean isPublic, @JsonIgnore @Nullable String credential,
                          long revision, Set<UUID> groupIds, Set<UUID> personaIds, DataBoundary dataBoundary) {
    @Override public @NonNull String toString() { return "LLMProvider[id=" + id + ", revision=" + revision + "]"; }
}
