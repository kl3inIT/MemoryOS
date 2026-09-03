package io.memoryos.worker;

import io.memoryos.objectstorage.ObjectUploadCleanupPort;

import java.util.Objects;

final class AbandonedObjectUploadCleanupTask {
    private final ObjectUploadCleanupPort cleanup;

    AbandonedObjectUploadCleanupTask(ObjectUploadCleanupPort cleanup) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup must not be null");
    }

    void execute() {
        cleanup.cleanupAbandoned();
    }
}
