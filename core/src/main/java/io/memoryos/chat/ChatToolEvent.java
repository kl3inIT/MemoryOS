package io.memoryos.chat;

import io.memoryos.retrieval.SearchFilters;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Product progress and evidence of one tool call; tool arguments and raw results are not streamed. */
public record ChatToolEvent(String toolCallId, String toolName, Stage stage, @Nullable ChatSource source,
        @Nullable QueryPlan search, List<ReadingDocument> documents, @Nullable Long durationMs) implements ChatActivityEvent {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** STARTED, COMPLETED and FAILED apply to every tool; the other stages belong to search and Web tools. */
    public enum Stage { STARTED, SEARCHING, SELECTING, EXPANDING, SOURCE, COMPLETED, FAILED }

    public record Call(String id, String name) {
        public Call {
            if (id == null || id.isBlank() || id.length() > 256 || name == null || !NAME.matcher(name).matches())
                throw new IllegalArgumentException("Invalid tool call");
        }
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

    public ChatToolEvent(Call call, Stage stage) {
        this(call.id(), call.name(), stage, null, null, List.of(), null);
    }

    public ChatToolEvent(Call call, ChatSource source) {
        this(call.id(), call.name(), Stage.SOURCE, source, null, List.of(), null);
    }

    public ChatToolEvent(Call call, QueryPlan search) {
        this(call.id(), call.name(), Stage.SEARCHING, null, search, List.of(), null);
    }

    public static ChatToolEvent reading(Call call, List<ReadingDocument> documents) {
        return new ChatToolEvent(call.id(), call.name(), Stage.EXPANDING, null, null, documents, null);
    }

    public static ChatToolEvent finished(Call call, boolean failed, @Nullable Long durationMs) {
        return new ChatToolEvent(call.id(), call.name(), failed ? Stage.FAILED : Stage.COMPLETED, null, null, List.of(), durationMs);
    }

    public Call call() {
        return new Call(toolCallId, toolName);
    }

    public ChatToolEvent {
        documents = List.copyOf(documents);
        new Call(toolCallId, toolName);
        if (stage == null || (stage == Stage.SOURCE) == (source == null) || (stage == Stage.SEARCHING) == (search == null)
                || documents.size() > 10 || !documents.isEmpty() && stage != Stage.EXPANDING
                || durationMs != null && (durationMs < 0 || stage != Stage.COMPLETED && stage != Stage.FAILED))
            throw new IllegalArgumentException("Invalid tool event");
    }
}
