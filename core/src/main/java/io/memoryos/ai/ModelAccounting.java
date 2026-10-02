package io.memoryos.ai;

import com.embabel.agent.core.AgentProcess;
import com.embabel.common.ai.model.LlmMetadata;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Usage of one turn or one model call. {@code used} is false when no model call was admitted, so nothing is billed;
 * unknown totals stay null and are never presented as zero. {@code complete} is false when some admitted call reported
 * no usage: the totals are then what the calls that did report used, and the turn still counts as having an unknown
 * cost, so a single silent call no longer erases the usage of every other call of the turn.
 */
public record ModelAccounting(@Nullable Long input, @Nullable Long output, @Nullable Double cost, long cacheRead, boolean used,
                              boolean complete) {
    public static final ModelAccounting NONE = new ModelAccounting(null, null, null, 0, false);

    /** Accounting whose totals are complete exactly when both token counts are known. */
    public ModelAccounting(@Nullable Long input, @Nullable Long output, @Nullable Double cost, long cacheRead, boolean used) {
        this(input, output, cost, cacheRead, used, input != null && output != null);
    }

    public static ModelAccounting of(List<? extends ModelGuard> guards, AgentProcess process, LlmMetadata metadata) {
        var used = guards.stream().filter(ModelGuard::used).toList();
        if (used.isEmpty()) return NONE;
        boolean complete = used.stream().allMatch(ModelGuard::usageKnown);
        if (used.stream().noneMatch(ModelGuard::usageReported)) return new ModelAccounting(null, null, null, 0, true, false);
        // Only calls that reported usage were recorded on the process, so its totals are theirs.
        var usage = process.usage();
        long cached = used.stream().mapToLong(ModelGuard::cacheReadTokens).sum();
        Double cost = metadata.getPricingModel() != null ? process.cost() : null;
        // Embabel prices every input token at the input rate; cached input is billed at the model's cache rate.
        if (cost != null && metadata.getPricingModel() instanceof ModelPricing pricing)
            cost = Math.max(0, cost - pricing.cacheDiscount(cached));
        long input = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens().longValue();
        long output = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens().longValue();
        return new ModelAccounting(input, output, cost, Math.min(cached, input), true, complete);
    }
}
