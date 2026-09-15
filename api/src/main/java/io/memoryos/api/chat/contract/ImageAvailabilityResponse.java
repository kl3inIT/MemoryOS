package io.memoryos.api.chat.contract;

import io.memoryos.chat.image.ImageProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record ImageAvailabilityResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean available,
        @Nullable ImageProvider provider, @Nullable String model) {}
