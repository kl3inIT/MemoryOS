package io.memoryos.api.chat.contract;

import io.memoryos.chat.interpreter.InterpreterClient;
import io.swagger.v3.oas.annotations.media.Schema;

/** Onyx {@code CodeInterpreterServerHealth} plus the service version; {@code error} is empty when healthy. */
public record InterpreterHealthResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean connected,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String error,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version) {
    public static InterpreterHealthResponse from(InterpreterClient.Health health) {
        return new InterpreterHealthResponse(health.connected(), health.error(), health.version());
    }
}
