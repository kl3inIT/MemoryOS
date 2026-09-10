package io.memoryos.chat;

import org.jspecify.annotations.Nullable;
import io.memoryos.retrieval.SearchFilters;
import java.util.List;
import java.util.UUID;

/** Product progress and evidence only; tool arguments and raw results are not streamed. */
public record ChatSearchEvent(String toolCallId, Stage stage, @Nullable ChatSource source,
        @Nullable QueryPlan search, List<ReadingDocument> documents) {
    public enum Stage { STARTED, SEARCHING, SELECTING, EXPANDING, SOURCE, COMPLETED, FAILED }
    public ChatSearchEvent(String toolCallId, Stage stage, @Nullable ChatSource source) {
        this(toolCallId, stage, source, null, List.of());
    }
    public record QueryPlan(List<String> queries, SearchFilters filters) {
        public QueryPlan {
            queries = List.copyOf(queries);
            if (queries.isEmpty() || queries.size() > 8 || queries.stream().anyMatch(q -> q.isBlank() || q.length() > 2000)
                    || filters == null) throw new IllegalArgumentException("Invalid search plan");
        }
    }
    public record ReadingDocument(UUID documentId, UUID generation, String title, int startOrdinal, int endOrdinal) {
        public ReadingDocument {
            if (documentId == null || generation == null || title == null || title.length() > 255
                    || startOrdinal < 0 || endOrdinal < startOrdinal || endOrdinal > 9999)
                throw new IllegalArgumentException("Invalid reading document");
        }
    }
    public ChatSearchEvent {
        documents = List.copyOf(documents);
        if (toolCallId == null || toolCallId.isBlank() || toolCallId.length() > 256 || stage == null
                || (stage == Stage.SOURCE) == (source == null) || (stage == Stage.SEARCHING) == (search == null)
                || documents.size() > 10 || !documents.isEmpty() && stage != Stage.EXPANDING)
            throw new IllegalArgumentException("Invalid search event");
    }
}
