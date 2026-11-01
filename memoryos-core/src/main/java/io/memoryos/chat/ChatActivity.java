package io.memoryos.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.memoryos.retrieval.SearchFilters;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Historical presentation of a turn's tool steps and provider reasoning, committed with the terminal outcome.
 * It holds allowlisted summaries only and grants no access. {@code position} orders steps and reasoning together;
 * {@code textOffset} is the answer length when the item started. {@code unavailableApps} are the turn's MCP servers
 * the person must connect first (MEM-273); omitted when empty, so a reply without them stores what it stored before.
 */
public record ChatActivity(List<ActivityStep> steps, List<ReasoningSegment> reasoning,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<UnavailableApp> unavailableApps) {
    public static final int MAX_ITEMS = 32;
    public static final int MAX_REASONING = 16_000;
    public static final String TRUNCATED = "\n…";
    public static final ChatActivity EMPTY = new ChatActivity(List.of(), List.of());

    public ChatActivity(List<ActivityStep> steps, List<ReasoningSegment> reasoning) {
        this(steps, reasoning, List.of());
    }

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

    /** An MCP server of the turn the person must connect, or sign in to again, before the assistant can use it. */
    public record UnavailableApp(UUID serverId, String serverName, Reason reason) {
        public enum Reason { NOT_CONNECTED, REAUTH_REQUIRED }

        public UnavailableApp {
            Objects.requireNonNull(serverId);
            Objects.requireNonNull(serverName);
            Objects.requireNonNull(reason);
        }
    }

    public ChatActivity {
        steps = List.copyOf(steps == null ? List.of() : steps);
        reasoning = List.copyOf(reasoning == null ? List.of() : reasoning);
        unavailableApps = List.copyOf(unavailableApps == null ? List.of() : unavailableApps);
        if (steps.size() > MAX_ITEMS || reasoning.size() > MAX_ITEMS || unavailableApps.size() > MAX_ITEMS
                || reasoning.stream().mapToInt(segment -> segment.text().length()).sum() > MAX_REASONING + TRUNCATED.length())
            throw new IllegalArgumentException("Invalid Chat activity");
    }
}
