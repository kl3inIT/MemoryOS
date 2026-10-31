package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Onyx {@code CodeInterpreterServerHealth} for the sandbox runtime, which runs the Sandbox group and run_python;
 * {@code error} is empty when healthy and {@code version} names the runtime.
 */
public record SandboxHealthResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean connected,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String error,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version) {
    public static SandboxHealthResponse of(boolean configured, boolean healthy) {
        String error = healthy ? "" : configured ? "The sandbox did not answer" : "The sandbox is not configured";
        return new SandboxHealthResponse(healthy, error, "OpenSandbox");
    }
}
