package io.memoryos.api.chat.contract;

import io.memoryos.ai.ReasoningEffort;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "ReasoningSelection")
public record ChatReasoningSelectionRequest(@Nullable ReasoningEffort reasoningEffort) {}
