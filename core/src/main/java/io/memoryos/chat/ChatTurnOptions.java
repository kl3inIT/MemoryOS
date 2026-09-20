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
 * @param sampling        creativity and reasoning level settled for this turn ({@link ChatSampling#NONE}: use the
 *                        model configuration as it stands)
 */
public record ChatTurnOptions(boolean searchEnabled, List<UUID> sourceIds,
                              @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit,
                              @Nullable Instant knowledgeCutoff, String taskPrompt, boolean codeInterpreter,
                              ChatSampling sampling) {
    public static final ChatTurnOptions DEFAULT = new ChatTurnOptions(true, List.of(), null, null);
    public ChatTurnOptions {
        sourceIds = List.copyOf(sourceIds);
        taskPrompt = taskPrompt == null ? "" : taskPrompt;
        sampling = sampling == null ? ChatSampling.NONE : sampling;
    }
    public ChatTurnOptions(boolean searchEnabled, List<UUID> sourceIds, @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit) {
        this(searchEnabled, sourceIds, contextTokenLimit, outputTokenLimit, null, "", true, ChatSampling.NONE);
    }
    public ChatTurnOptions(boolean searchEnabled, List<UUID> sourceIds, @Nullable Integer contextTokenLimit,
                           @Nullable Integer outputTokenLimit, @Nullable Instant knowledgeCutoff, String taskPrompt,
                           boolean codeInterpreter) {
        this(searchEnabled, sourceIds, contextTokenLimit, outputTokenLimit, knowledgeCutoff, taskPrompt, codeInterpreter,
                ChatSampling.NONE);
    }

    /** The same restrictions with this turn's creativity and reasoning level. */
    public ChatTurnOptions withSampling(ChatSampling value) {
        return new ChatTurnOptions(searchEnabled, sourceIds, contextTokenLimit, outputTokenLimit, knowledgeCutoff,
                taskPrompt, codeInterpreter, value);
    }
}
