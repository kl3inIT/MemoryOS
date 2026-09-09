package io.memoryos.connector;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record SourceOperationPage(
        List<SourceIndexAttemptView> items, @Nullable String nextCursor, long totalItems
) {}
