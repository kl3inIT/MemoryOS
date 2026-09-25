package io.memoryos.ai;

import org.jspecify.annotations.Nullable;

/**
 * What one turn asks of the model beyond its configuration: the creativity the member set and the reasoning level
 * settled for this conversation. Both are null when nothing outranks the model configuration, and the provider
 * adapter then sends exactly what the configuration says.
 *
 * <p>{@code pinnedReasoning} distinguishes Onyx's two sources: a level pinned on this conversation outranks the model
 * configuration, while a member's default only applies to a model whose configuration names no level.
 */
public record ModelSampling(@Nullable Double temperature, @Nullable ReasoningEffort reasoningEffort,
                           boolean pinnedReasoning) {
    public static final ModelSampling NONE = new ModelSampling(null, null, false);

    public ModelSampling(@Nullable Double temperature, @Nullable ReasoningEffort reasoningEffort) {
        this(temperature, reasoningEffort, false);
    }

    public ModelSampling {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2))
            throw AiException.invalid("Creativity must be between 0 and 2.");
    }

    public boolean isEmpty() {
        return temperature == null && reasoningEffort == null;
    }
}
