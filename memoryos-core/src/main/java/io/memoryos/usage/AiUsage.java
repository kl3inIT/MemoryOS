package io.memoryos.usage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One AI call, or the calls of one chat turn, to add to the daily rollup.
 *
 * @param actor         null for system work such as document indexing
 * @param dataBoundary  the provider's {@code INTERNAL} or {@code EXTERNAL} label at call time; null outside the chat catalog
 * @param cost             known cost in USD; null when the model has no price or the provider reported no usage
 * @param unknownCostCalls how many of {@code calls} have a cost that is not known, wholly or in part: every call when
 *                         {@code cost} is null, and a call some of whose model calls reported no usage
 */
public record AiUsage(UUID tenant, @Nullable UUID actor, AiUsageFlow flow, String providerName, String modelName,
                      @Nullable UUID providerId, @Nullable UUID modelConfigurationId, @Nullable String dataBoundary,
                      long calls, long inputTokens, long outputTokens, long cacheReadTokens, long imageCount,
                      double audioSeconds, @Nullable Double cost, Instant at, long unknownCostCalls) {
    public AiUsage {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(flow);
        Objects.requireNonNull(at);
        providerName = bounded(providerName);
        modelName = bounded(modelName);
        if (calls < 1 || inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0 || cacheReadTokens > inputTokens
                || imageCount < 0 || !Double.isFinite(audioSeconds) || audioSeconds < 0
                || (cost != null && (!Double.isFinite(cost) || cost < 0)))
            throw new IllegalArgumentException("Invalid AI usage.");
        if (dataBoundary != null && !dataBoundary.equals("INTERNAL") && !dataBoundary.equals("EXTERNAL"))
            throw new IllegalArgumentException("Invalid data boundary.");
        if (cost == null) unknownCostCalls = calls;
        if (unknownCostCalls < 0 || unknownCostCalls > calls) throw new IllegalArgumentException("Invalid AI usage.");
    }

    /** A usage whose cost is unknown exactly when it is null. */
    public AiUsage(UUID tenant, @Nullable UUID actor, AiUsageFlow flow, String providerName, String modelName,
                   @Nullable UUID providerId, @Nullable UUID modelConfigurationId, @Nullable String dataBoundary,
                   long calls, long inputTokens, long outputTokens, long cacheReadTokens, long imageCount,
                   double audioSeconds, @Nullable Double cost, Instant at) {
        this(tenant, actor, flow, providerName, modelName, providerId, modelConfigurationId, dataBoundary, calls, inputTokens,
                outputTokens, cacheReadTokens, imageCount, audioSeconds, cost, at, cost == null ? calls : 0);
    }

    /** A token-priced call whose usage the provider reported; a null cost marks a model without a price. */
    public static AiUsage tokens(UUID tenant, @Nullable UUID actor, AiUsageFlow flow, String providerName, String modelName,
                                 @Nullable UUID providerId, @Nullable UUID modelConfigurationId, @Nullable String dataBoundary,
                                 long input, long output, long cacheRead, @Nullable Double cost, Instant at) {
        return tokens(tenant, actor, flow, providerName, modelName, providerId, modelConfigurationId, dataBoundary, input,
                output, cacheRead, cost, true, at);
    }

    /**
     * A token-priced call; {@code complete} is false when some of its model calls reported no usage, so the totals are
     * what the others used and the call still counts as having an unknown cost.
     */
    public static AiUsage tokens(UUID tenant, @Nullable UUID actor, AiUsageFlow flow, String providerName, String modelName,
                                 @Nullable UUID providerId, @Nullable UUID modelConfigurationId, @Nullable String dataBoundary,
                                 long input, long output, long cacheRead, @Nullable Double cost, boolean complete, Instant at) {
        return new AiUsage(tenant, actor, flow, providerName, modelName, providerId, modelConfigurationId, dataBoundary,
                1, input, output, cacheRead, 0, 0, cost, at, cost != null && complete ? 0 : 1);
    }

    /** A call that ran but whose usage is unknown: counted, never given invented totals. */
    public static AiUsage unknown(UUID tenant, @Nullable UUID actor, AiUsageFlow flow, String providerName, String modelName,
                                  @Nullable UUID providerId, @Nullable UUID modelConfigurationId, @Nullable String dataBoundary, Instant at) {
        return new AiUsage(tenant, actor, flow, providerName, modelName, providerId, modelConfigurationId, dataBoundary,
                1, 0, 0, 0, 0, 0, null, at);
    }

    private static String bounded(String name) {
        if (name == null || name.isBlank()) return "unknown";
        String trimmed = name.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200);
    }
}
