package io.memoryos.sandbox;

/** A file or directory in a sandbox. */
public record SandboxEntry(String path, boolean directory, long sizeBytes) {}
