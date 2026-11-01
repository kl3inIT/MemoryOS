package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatHistoryVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(name = "ChatHistoryVisibilityRequest")
public record ChatHistoryVisibilityRequest(@NotNull ChatHistoryVisibility visibility,
                                           @Min(0) long revision) {}
