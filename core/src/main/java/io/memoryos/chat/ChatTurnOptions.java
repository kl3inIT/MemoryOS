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
 * @param topicRules      the Tenant's blocked topics as an instruction for the answer model, set only for a turn whose
 *                        guardrail check returned no verdict; empty otherwise
 */
public record ChatTurnOptions(boolean searchEnabled, List<UUID> sourceIds, boolean sourcesRestricted,
                              @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit,
                              @Nullable Instant knowledgeCutoff, String taskPrompt, boolean codeInterpreter,
                              ModelSampling sampling, boolean grounded, String topicRules) {
    public static final ChatTurnOptions DEFAULT = builder().build();
    public ChatTurnOptions {
        sourceIds = List.copyOf(sourceIds);
        taskPrompt = taskPrompt == null ? "" : taskPrompt;
        topicRules = topicRules == null ? "" : topicRules;
        sampling = sampling == null ? ModelSampling.NONE : sampling;
    }
    /**
     * Options that restrict nothing: search and the code interpreter offered, every readable Source, the model's own
     * limits, no task prompt, no sampling override and not grounded.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean searchEnabled = true;
        private List<UUID> sourceIds = List.of();
        private boolean sourcesRestricted;
        private @Nullable Integer contextTokenLimit;
        private @Nullable Integer outputTokenLimit;
        private @Nullable Instant knowledgeCutoff;
        private String taskPrompt = "";
        private boolean codeInterpreter = true;
        private ModelSampling sampling = ModelSampling.NONE;
        private boolean grounded;

        private Builder() {
        }

        public Builder searchEnabled(boolean value) { searchEnabled = value; return this; }
        public Builder sourceIds(List<UUID> value) { sourceIds = value; return this; }
        public Builder sourcesRestricted(boolean value) { sourcesRestricted = value; return this; }
        public Builder contextTokenLimit(@Nullable Integer value) { contextTokenLimit = value; return this; }
        public Builder outputTokenLimit(@Nullable Integer value) { outputTokenLimit = value; return this; }
        public Builder knowledgeCutoff(@Nullable Instant value) { knowledgeCutoff = value; return this; }
        public Builder taskPrompt(String value) { taskPrompt = value; return this; }
        public Builder codeInterpreter(boolean value) { codeInterpreter = value; return this; }
        public Builder sampling(ModelSampling value) { sampling = value; return this; }
        public Builder grounded(boolean value) { grounded = value; return this; }

        public ChatTurnOptions build() {
            return new ChatTurnOptions(searchEnabled, sourceIds, sourcesRestricted, contextTokenLimit, outputTokenLimit,
                    knowledgeCutoff, taskPrompt, codeInterpreter, sampling, grounded, "");
        }
    }

    /** The same restrictions with this turn's creativity and reasoning level. */
    public ChatTurnOptions withSampling(ModelSampling value) {
        return new ChatTurnOptions(searchEnabled, sourceIds, sourcesRestricted, contextTokenLimit, outputTokenLimit,
                knowledgeCutoff, taskPrompt, codeInterpreter, value, grounded, topicRules);
    }

    /** The same restrictions, answering from documents only or not; the agent's own search setting is kept. */
    public ChatTurnOptions withGrounded(boolean value) {
        return new ChatTurnOptions(searchEnabled, sourceIds, sourcesRestricted, contextTokenLimit, outputTokenLimit,
                knowledgeCutoff, taskPrompt, codeInterpreter, sampling, value, topicRules);
    }

    /** The same turn, with the blocked topics left to the answer model because the guardrail check gave no verdict. */
    public ChatTurnOptions withTopicRules(String value) {
        return new ChatTurnOptions(searchEnabled, sourceIds, sourcesRestricted, contextTokenLimit, outputTokenLimit,
                knowledgeCutoff, taskPrompt, codeInterpreter, sampling, grounded, value);
    }

    /** Whether the turn offers {@code search_knowledge}: the agent allows it, or the turn is grounded. */
    public boolean searches() { return searchEnabled || grounded; }

    /** The Sources a turn may search, or {@code null} when the agent restricts nothing. */
    public @Nullable List<UUID> sourceAllowlist() { return sourcesRestricted ? sourceIds : null; }
}
