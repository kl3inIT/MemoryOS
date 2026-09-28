package io.memoryos.chat;

import io.memoryos.ai.ModelSampling;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Agent restrictions resolved for one turn.
 *
 * @param sourcesRestricted whether the agent attaches Sources or Document Sets at all. An agent that attaches none
 *                          searches every authorized Source, while one whose attachments resolve to nothing for this
 *                          actor searches nothing: an unusable attachment must never widen retrieval.
 *
 * @param knowledgeCutoff lower bound for document update time (Onyx {@code search_start_date})
 * @param taskPrompt      agent reminder sent after the latest message of every inference (Onyx {@code task_prompt})
 * @param codeInterpreter whether the agent allows {@code run_python}
 * @param sampling        creativity and reasoning level settled for this turn ({@link ModelSampling#NONE}: use the
 *                        model configuration as it stands)
 * @param grounded        MEM-195: the turn answers from the organization's documents only, because the Tenant or the
 *                        agent says so; it searches whatever the agent's search tool setting ({@link #searches()})
 */
public record ChatTurnOptions(boolean searchEnabled, List<UUID> sourceIds, boolean sourcesRestricted,
                              @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit,
                              @Nullable Instant knowledgeCutoff, String taskPrompt, boolean codeInterpreter,
                              ModelSampling sampling, boolean grounded) {
    public static final ChatTurnOptions DEFAULT = new ChatTurnOptions(true, List.of(), null, null);
    public ChatTurnOptions {
        sourceIds = List.copyOf(sourceIds);
        taskPrompt = taskPrompt == null ? "" : taskPrompt;
        sampling = sampling == null ? ModelSampling.NONE : sampling;
    }
    public ChatTurnOptions(boolean searchEnabled, List<UUID> sourceIds, @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit) {
        this(searchEnabled, sourceIds, false, contextTokenLimit, outputTokenLimit, null, "", true,
                ModelSampling.NONE, false);
    }
    public ChatTurnOptions(boolean searchEnabled, List<UUID> sourceIds, boolean sourcesRestricted,
                           @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit,
                           @Nullable Instant knowledgeCutoff, String taskPrompt, boolean codeInterpreter) {
        this(searchEnabled, sourceIds, sourcesRestricted, contextTokenLimit, outputTokenLimit, knowledgeCutoff,
                taskPrompt, codeInterpreter, ModelSampling.NONE, false);
    }

    /** The same restrictions with this turn's creativity and reasoning level. */
    public ChatTurnOptions withSampling(ModelSampling value) {
        return new ChatTurnOptions(searchEnabled, sourceIds, sourcesRestricted, contextTokenLimit, outputTokenLimit,
                knowledgeCutoff, taskPrompt, codeInterpreter, value, grounded);
    }

    /** The same restrictions, answering from documents only or not; the agent's own search setting is kept. */
    public ChatTurnOptions withGrounded(boolean value) {
        return new ChatTurnOptions(searchEnabled, sourceIds, sourcesRestricted, contextTokenLimit, outputTokenLimit,
                knowledgeCutoff, taskPrompt, codeInterpreter, sampling, value);
    }

    /** Whether the turn offers {@code search_knowledge}: the agent allows it, or the turn is grounded. */
    public boolean searches() { return searchEnabled || grounded; }

    /** The Sources a turn may search, or {@code null} when the agent restricts nothing. */
    public @Nullable List<UUID> sourceAllowlist() { return sourcesRestricted ? sourceIds : null; }
}
