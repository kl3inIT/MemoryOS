package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Onyx {@code CodeInterpreterServerHealth} plus the service version; {@code error} is empty when healthy. Since MEM-271
 * it reports the sandbox runtime, which runs the Sandbox group and run_python, and {@code version} names it.
 */
public record InterpreterHealthResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean connected,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String error,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version) {
    public static InterpreterHealthResponse of(boolean configured, boolean healthy) {
        String error = healthy ? "" : configured ? "The sandbox did not answer" : "The sandbox is not configured";
        return new InterpreterHealthResponse(healthy, error, "OpenSandbox");
    }
}
