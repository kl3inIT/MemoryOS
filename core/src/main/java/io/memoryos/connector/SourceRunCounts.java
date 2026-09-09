package io.memoryos.connector;

import org.jspecify.annotations.Nullable;

public record SourceRunCounts(
        @Nullable Long scanned, @Nullable Long acquired, @Nullable Long published,
        @Nullable Long unchanged, @Nullable Long alreadyPending, @Nullable Long acquisitionFailed,
        @Nullable Long indexingFailed, @Nullable Long skipped, @Nullable Long removed,
        @Nullable Long indexingPending, @Nullable Long indexingSuperseded, @Nullable Long indexingCancelled
) {}
