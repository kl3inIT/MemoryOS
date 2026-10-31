package io.memoryos.sandbox;

import java.time.Duration;

/** A shell command run with {@code bash -c} as {@code uid} in {@code workingDirectory}, stopped after {@code timeout}. */
public record SandboxCommand(String command, String workingDirectory, int uid, Duration timeout) {
    public SandboxCommand {
        if (command.isBlank()) throw new IllegalArgumentException("A command is required");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("A command needs a timeout");
    }
}
