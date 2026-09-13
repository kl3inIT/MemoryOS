package io.memoryos.api.chat.contract;

import io.memoryos.chat.web.WebProvider;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.UUID;

public record WebAvailabilityResponse(boolean searchAvailable, boolean contentAvailable,
        @Nullable WebProvider searchProvider, @Nullable WebProvider contentProvider,
        List<UUID> automaticModelIds, List<UUID> requiredModelIds,
        @Nullable UUID inheritedModelId) {}
