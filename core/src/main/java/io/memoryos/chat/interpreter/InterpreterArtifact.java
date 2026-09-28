package io.memoryos.chat.interpreter;

import io.memoryos.objectstorage.ObjectKey;
import org.jspecify.annotations.Nullable;

/** {@code previewKey} is the cached PDF rendering of a presentation, when one was converted. */
public record InterpreterArtifact(ObjectKey key, String filename, String mediaType, @Nullable ObjectKey previewKey) {
    public InterpreterArtifact(ObjectKey key, String filename, String mediaType) {
        this(key, filename, mediaType, null);
    }
}
