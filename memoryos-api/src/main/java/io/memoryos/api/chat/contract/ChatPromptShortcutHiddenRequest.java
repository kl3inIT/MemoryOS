package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "HiddenRequest")
public record ChatPromptShortcutHiddenRequest(boolean hidden) {}
