package io.memoryos.chat.catalog;

import io.memoryos.chat.ChatException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Declared deployment limits/capabilities, not capabilities inferred from a model name. */
public record ModelSettings(int contextWindow, int maxOutputTokens, Capabilities capabilities,
                            Map<String, Object> options, @Nullable Pricing pricing, String tokenizerProfile) {
    public ModelSettings {
        if (contextWindow < 256 || contextWindow > 10000000 || maxOutputTokens < 1
                || maxOutputTokens >= contextWindow || capabilities == null || options == null || options.size() > 32)
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

    public record Pricing(double inputPerMillion, double outputPerMillion) {
        public Pricing {
            if (!Double.isFinite(inputPerMillion) || !Double.isFinite(outputPerMillion)
                    || inputPerMillion < 0 || outputPerMillion < 0)
                throw ChatException.invalid("Invalid model pricing.");
        }
    }
}
