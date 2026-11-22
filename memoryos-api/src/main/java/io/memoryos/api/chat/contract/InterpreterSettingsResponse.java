package io.memoryos.api.chat.contract;

import io.memoryos.chat.interpreter.InterpreterService;
import io.swagger.v3.oas.annotations.media.Schema;

public record InterpreterSettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether this deployment has a Code Interpreter service")
        boolean configured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision) {
    public static InterpreterSettingsResponse from(InterpreterService.Settings settings) {
        return new InterpreterSettingsResponse(settings.configured(), settings.enabled(), settings.revision());
    }
}
