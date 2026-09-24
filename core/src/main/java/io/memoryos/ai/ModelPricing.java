package io.memoryos.ai;

import com.embabel.common.ai.model.PricingModel;

/**
 * Embabel pricing for a catalog model that also knows its cached-input rate. Embabel prices every input token at the
 * input rate, so a turn's cost is corrected with {@link #cacheDiscount(long)} once its cached tokens are known.
 */
public final class ModelPricing implements PricingModel {
    private final ModelSettings.Pricing pricing;

    private ModelPricing(ModelSettings.Pricing pricing) { this.pricing = pricing; }

    public static ModelPricing of(ModelSettings.Pricing pricing) { return new ModelPricing(pricing); }

    @Override public double usdPerInputToken() { return pricing.inputPerMillion() / 1_000_000; }

    @Override public double usdPerOutputToken() { return pricing.outputPerMillion() / 1_000_000; }

    /**
     * What cached input tokens cost less than the input rate: (input − cached rate) × cached tokens (Onyx, Orca). It is
     * negative when a manager prices cache reads above input, so the difference is added rather than dropped.
     */
    public double cacheDiscount(long cachedInputTokens) {
        return (pricing.inputPerMillion() - pricing.cachedInputRate()) * Math.max(0, cachedInputTokens) / 1_000_000;
    }
}
