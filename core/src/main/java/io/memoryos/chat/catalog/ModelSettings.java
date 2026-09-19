package io.memoryos.chat.catalog;

import io.memoryos.chat.ChatException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Declared deployment limits/capabilities, not capabilities inferred from a model name. A {@code null} output limit
 * means the provider publishes none: as Onyx, no output cap is sent and the provider's own default applies.
 */
public record ModelSettings(int contextWindow, @Nullable Integer maxOutputTokens, Capabilities capabilities,
                            Map<String, Object> options, @Nullable Pricing pricing, String tokenizerProfile) {
    public ModelSettings {
        if (contextWindow < 256 || contextWindow > 10000000
                || maxOutputTokens != null && (maxOutputTokens < 1 || maxOutputTokens >= contextWindow) || capabilities == null || options == null || options.size() > 32)
            throw ChatException.invalid("Invalid model limits or capabilities.");
        if (tokenizerProfile == null || tokenizerProfile.isBlank())
            throw ChatException.invalid("A tokenizer profile is required.");
        // Flat scalars only. Provider adapters validate the allowed keys, types and ranges.
        if (options.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null || e.getKey().length() > 64
                || !(e.getValue() instanceof String || e.getValue() instanceof Number || e.getValue() instanceof Boolean)
                || e.getValue().toString().length() > 200))
            throw ChatException.invalid("Invalid model options.");
        options = Map.copyOf(options);
    }

    public record Capabilities(boolean streaming, boolean toolCalling, boolean vision, boolean reasoning) {}

    /**
     * USD per million tokens. {@code cachedInputPerMillion} prices input the provider served from its prompt cache
     * (Onyx {@code cache_read_cost_per_mtok}); without it cached input costs the input rate.
     */
    public record Pricing(double inputPerMillion, double outputPerMillion, @Nullable Double cachedInputPerMillion) {
        public Pricing {
            if (!Double.isFinite(inputPerMillion) || !Double.isFinite(outputPerMillion)
                    || inputPerMillion < 0 || outputPerMillion < 0
                    || (cachedInputPerMillion != null && (!Double.isFinite(cachedInputPerMillion) || cachedInputPerMillion < 0)))
                throw ChatException.invalid("Invalid model pricing.");
        }

        public Pricing(double inputPerMillion, double outputPerMillion) { this(inputPerMillion, outputPerMillion, null); }

        public double cachedInputRate() { return cachedInputPerMillion == null ? inputPerMillion : cachedInputPerMillion; }
    }
}
