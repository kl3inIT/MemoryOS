package io.memoryos.retrieval;

import io.memoryos.connector.SourceType;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * {@code sourceTypes} and {@code documentSetIds} narrow results to currently readable Documents; neither widens Source scope.
 */
public record SearchRequest(String query, List<String> mediaTypes, Instant updatedSince, int page, int pageSize,
        List<SourceType> sourceTypes, List<java.util.UUID> documentSetIds) {
    public SearchRequest {
        query = query == null ? "" : query.strip();
        mediaTypes = mediaTypes == null ? List.of() : mediaTypes;
        sourceTypes = sourceTypes == null ? List.of() : sourceTypes;
        documentSetIds = documentSetIds == null ? List.of() : documentSetIds;
        if (query.isEmpty() || query.length() > 1000 || mediaTypes.size() > 10
                || mediaTypes.stream().anyMatch(t -> t == null || !t.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))
                || sourceTypes.size() > 10 || sourceTypes.stream().anyMatch(Objects::isNull)
                || documentSetIds.size() > 10 || documentSetIds.stream().anyMatch(Objects::isNull)
                || page < 0 || page > 49 || pageSize < 1 || pageSize > 20) {
            throw new SearchRequestException();
        }
        mediaTypes = List.copyOf(mediaTypes);
        sourceTypes = List.copyOf(new LinkedHashSet<>(sourceTypes));
        documentSetIds = List.copyOf(new LinkedHashSet<>(documentSetIds));
    }

    @Override public @NonNull String toString() {
        return "SearchRequest[query=REDACTED, page=" + page + ", pageSize=" + pageSize + "]";
    }
}
