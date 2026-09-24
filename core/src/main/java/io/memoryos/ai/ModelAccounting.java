package io.memoryos.ai;

import com.embabel.agent.core.AgentProcess;
import com.embabel.common.ai.model.LlmMetadata;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Usage of one turn or one model call. {@code used} is false when no model call was admitted, so nothing is billed;
 * unknown totals stay null and are never presented as zero.
 */
public record ModelAccounting(@Nullable Long input, @Nullable Long output, @Nullable Double cost, long cacheRead, boolean used) {
    public static final ModelAccounting NONE = new ModelAccounting(null, null, null, 0, false);

    public static ModelAccounting of(List<? extends ModelGuard> guards, AgentProcess process, LlmMetadata metadata) {
        var used = guards.stream().filter(ModelGuard::used).toList();
        if (used.isEmpty()) return NONE;
        boolean known = used.stream().allMatch(ModelGuard::usageKnown);
        var usage = process.usage();
        long cached = used.stream().mapToLong(ModelGuard::cacheReadTokens).sum();
        Double cost = known && metadata.getPricingModel() != null ? process.cost() : null;
        // Embabel prices every input token at the input rate; cached input is billed at the model's cache rate.
        if (cost != null && metadata.getPricingModel() instanceof ChatModelPricing pricing)
            cost = Math.max(0, cost - pricing.cacheDiscount(cached));
        return new ModelAccounting(known && usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : null,
                known && usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : null,
                cost, known ? cached : 0, true);
    }
}
