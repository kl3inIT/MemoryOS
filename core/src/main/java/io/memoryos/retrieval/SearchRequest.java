package io.memoryos.retrieval;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;

public record SearchRequest(String query, List<String> mediaTypes, Instant updatedSince, int page, int pageSize) {
    public SearchRequest {
        query = query == null ? "" : query.strip();
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        if (query.isEmpty() || query.length() > 1000 || mediaTypes.size() > 10
                || mediaTypes.stream().anyMatch(t -> t == null || !t.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))
                || page < 0 || page > 49 || pageSize < 1 || pageSize > 20) {
            throw new SearchRequestException();
        }
    }

    @Override public @NonNull String toString() {
        return "SearchRequest[query=REDACTED, page=" + page + ", pageSize=" + pageSize + "]";
    }
}
