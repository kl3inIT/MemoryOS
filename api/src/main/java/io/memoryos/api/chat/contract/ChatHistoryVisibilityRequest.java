package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChatHistoryVisibilityRequest")
public record ChatHistoryVisibilityRequest(@jakarta.validation.constraints.NotNull io.memoryos.chat.ChatHistoryVisibility visibility,
                                           @jakarta.validation.constraints.Min(0) long revision) {}
