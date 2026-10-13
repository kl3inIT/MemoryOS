package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChatMemoryEnabledInput")
public record ChatMemoryEnabledRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled) {}
