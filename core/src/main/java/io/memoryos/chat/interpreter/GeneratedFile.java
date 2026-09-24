package io.memoryos.chat.interpreter;

import java.util.UUID;

/** {@code chart} says chart data is stored beside the PNG; it is read separately to keep lists small. */
public record GeneratedFile(UUID id, String filename, String mediaType, long sizeBytes, boolean chart, boolean deleted) {}
