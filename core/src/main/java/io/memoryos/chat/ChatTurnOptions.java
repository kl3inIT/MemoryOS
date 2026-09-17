package io.memoryos.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Agent restrictions resolved for one turn; empty sourceIds means all currently authorized sources.
 *
 * @param knowledgeCutoff lower bound for document update time (Onyx {@code search_start_date})
 * @param taskPrompt      agent reminder sent after the latest message of every inference (Onyx {@code task_prompt})
 * @param codeInterpreter whether the agent allows {@code run_python}
 */
public record ChatTurnOptions(boolean searchEnabled, List<UUID> sourceIds,
                              @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit,
                              @Nullable Instant knowledgeCutoff, String taskPrompt, boolean codeInterpreter) {
    public static final ChatTurnOptions DEFAULT = new ChatTurnOptions(true, List.of(), null, null);
    public ChatTurnOptions { sourceIds = List.copyOf(sourceIds); taskPrompt = taskPrompt == null ? "" : taskPrompt; }
    public ChatTurnOptions(boolean searchEnabled, List<UUID> sourceIds, @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit) {
        this(searchEnabled, sourceIds, contextTokenLimit, outputTokenLimit, null, "", true);
    }
}
