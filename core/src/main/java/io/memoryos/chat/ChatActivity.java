package io.memoryos.chat;

import io.memoryos.retrieval.SearchFilters;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Historical presentation of a turn's tool steps and provider reasoning, committed with the terminal outcome.
 * It holds allowlisted summaries only and grants no access. {@code position} orders steps and reasoning together;
 * {@code textOffset} is the answer length when the item started.
 */
public record ChatActivity(List<ActivityStep> steps, List<ReasoningSegment> reasoning) {
    public static final int MAX_ITEMS = 32;
    public static final int MAX_REASONING = 16_000;
    public static final String TRUNCATED = "\n…";
    public static final ChatActivity EMPTY = new ChatActivity(List.of(), List.of());

    public enum StepStatus { RUNNING, COMPLETED, FAILED }

    public record ActivityStep(int position, String toolCallId, String toolName, StepStatus status, Instant startedAt,
            @Nullable Long durationMs, int textOffset, List<String> queries, @Nullable SearchFilters filters,
            List<ChatToolEvent.ReadingDocument> documents, List<Integer> citations, ChatToolEvent.@Nullable Failure failure) {
        public ActivityStep(int position, String toolCallId, String toolName, StepStatus status, Instant startedAt,
                @Nullable Long durationMs, int textOffset, List<String> queries, @Nullable SearchFilters filters,
                List<ChatToolEvent.ReadingDocument> documents, List<Integer> citations) {
            this(position, toolCallId, toolName, status, startedAt, durationMs, textOffset, queries, filters, documents, citations, null);
        }

        public ActivityStep {
            queries = List.copyOf(queries);
            documents = List.copyOf(documents);
            citations = List.copyOf(citations);
            new ChatToolEvent.Call(toolCallId, toolName);
            if (position < 0 || textOffset < 0 || status == null || startedAt == null || durationMs != null && durationMs < 0
                    || queries.size() > 8 || queries.stream().anyMatch(q -> q.isBlank() || q.length() > 500)
                    || documents.size() > 10 || citations.stream().anyMatch(c -> c < 1)
                    || failure != null && status != StepStatus.FAILED)
                throw new IllegalArgumentException("Invalid activity step");
        }
    }

    public record ReasoningSegment(int position, int textOffset, String text) {
        public ReasoningSegment {
            if (position < 0 || textOffset < 0 || text == null || text.isEmpty() || text.length() > MAX_REASONING + TRUNCATED.length())
                throw new IllegalArgumentException("Invalid reasoning segment");
        }
    }

    public ChatActivity {
        steps = List.copyOf(steps == null ? List.of() : steps);
        reasoning = List.copyOf(reasoning == null ? List.of() : reasoning);
        if (steps.size() > MAX_ITEMS || reasoning.size() > MAX_ITEMS
                || reasoning.stream().mapToInt(segment -> segment.text().length()).sum() > MAX_REASONING + TRUNCATED.length())
            throw new IllegalArgumentException("Invalid Chat activity");
    }
}
