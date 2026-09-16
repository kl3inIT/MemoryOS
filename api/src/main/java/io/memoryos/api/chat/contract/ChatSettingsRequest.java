package io.memoryos.api.chat.contract;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ChatSettingsRequest(@NotNull Boolean deepResearchEnabled, @Min(0) long revision) {
}
