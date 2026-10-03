package io.memoryos.ai;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One task's stored model and reasoning level; a null level is the task's own default. */
public record FlowModelDefault(ModelFlow flow, @Nullable UUID modelConfigurationId, @Nullable ReasoningEffort reasoningEffort,
                               long revision) {
    /** The level the task runs at. */
    public ReasoningEffort effort() {
        return reasoningEffort != null ? reasoningEffort : flow.defaultEffort();
    }
}
