package io.memoryos.ai;

import com.embabel.common.ai.model.PricingModel;

/**
 * Embabel pricing for a catalog model that also knows its cached-input rates. Embabel prices every input token at the
 * input rate, so a turn's cost is corrected with {@link #cacheDiscount(long)} and {@link #cacheWritePremium(long)} once
 * its cached tokens are known.
 */
public final class ModelPricing implements PricingModel {
    private final ModelSettings.Pricing pricing;
    private final double cacheWriteRate;

    private ModelPricing(ModelSettings.Pricing pricing, double cacheWriteRate) {
        this.pricing = pricing;
        this.cacheWriteRate = cacheWriteRate;
    }

    /** Pricing whose provider bills cache writes as ordinary input. */
    public static ModelPricing of(ModelSettings.Pricing pricing) { return new ModelPricing(pricing, 1); }

    /** Pricing whose provider bills a cache write at {@code cacheWriteRate} times the input rate (MEM-269). */
    public static ModelPricing of(ModelSettings.Pricing pricing, double cacheWriteRate) {
        if (!Double.isFinite(cacheWriteRate) || cacheWriteRate < 1) throw new IllegalArgumentException("Invalid cache write rate");
        return new ModelPricing(pricing, cacheWriteRate);
    }

    @Override public double usdPerInputToken() { return pricing.inputPerMillion() / 1_000_000; }

    @Override public double usdPerOutputToken() { return pricing.outputPerMillion() / 1_000_000; }

    /**
     * What cached input tokens cost less than the input rate: (input − cached rate) × cached tokens (Onyx, Orca). It is
     * negative when a manager prices cache reads above input, so the difference is added rather than dropped.
     */
    public double cacheDiscount(long cachedInputTokens) {
        return (pricing.inputPerMillion() - pricing.cachedInputRate()) * Math.max(0, cachedInputTokens) / 1_000_000;
    }

    /** What input written to the prompt cache costs above the input rate; zero where writes are ordinary input. */
    public double cacheWritePremium(long writtenInputTokens) {
        return (cacheWriteRate - 1) * pricing.inputPerMillion() * Math.max(0, writtenInputTokens) / 1_000_000;
    }
}
