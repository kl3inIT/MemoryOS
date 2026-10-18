package io.memoryos.api.chat.contract;

import io.memoryos.chat.interpreter.InterpreterService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record SandboxSettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether this deployment has a sandbox")
        boolean configured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The only domains a sandbox may reach, set by the deployment; empty denies all egress")
        List<String> allowedDomains) {
    public static SandboxSettingsResponse from(InterpreterService.Settings settings, List<String> allowedDomains) {
        return new SandboxSettingsResponse(settings.configured(), settings.enabled(), settings.revision(),
                allowedDomains);
    }
}
