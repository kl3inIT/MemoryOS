package io.memoryos.retrieval;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** One page of Source documents; {@code nextCursor} is null on the last page. */
public record ShelfPage(List<ShelfDocument> items, @Nullable String nextCursor) {
    public ShelfPage {
        items = List.copyOf(items);
    }
}
