package io.memoryos.connector;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record SourceItemPage(List<SourceItemView> items, @Nullable String nextCursor, long totalItems) {}
