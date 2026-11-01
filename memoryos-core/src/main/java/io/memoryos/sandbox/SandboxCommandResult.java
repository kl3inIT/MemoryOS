package io.memoryos.sandbox;

import java.time.Duration;

/**
 * How a command ended. {@code timedOut} is the runtime's verdict that it was stopped at its timeout; the exit code is
 * then whatever the runtime reported (OpenSandbox: −1).
 */
public record SandboxCommandResult(int exitCode, String stdout, String stderr, boolean timedOut, Duration elapsed) {}
