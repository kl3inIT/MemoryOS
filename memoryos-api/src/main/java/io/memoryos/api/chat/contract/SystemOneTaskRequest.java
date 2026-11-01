package io.memoryos.api.chat.contract;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record SystemOneTaskRequest(@NotNull UUID connectionId, @Positive long revision) {}
