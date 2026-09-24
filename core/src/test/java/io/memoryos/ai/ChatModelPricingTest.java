package io.memoryos.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChatModelPricingTest {
    @Test void cachedInputCostsTheCacheReadRateAndDefaultsToTheInputRate() {
        // gpt-5.1: $1.25 input, $0.125 cache read, $10 output per million tokens.
        var priced = ChatModelPricing.of(new ModelSettings.Pricing(1.25, 10, 0.125));
        double embabel = priced.costOf(1_000_000, 100_000);
        assertEquals(1.25 + 1.0, embabel, 1e-9);
        // 800k of the million input tokens were cache reads: (200k × 1.25 + 800k × 0.125) / 1M + output.
        assertEquals(0.25 + 0.1 + 1.0, embabel - priced.cacheDiscount(800_000), 1e-9);
        var unpricedCache = ChatModelPricing.of(new ModelSettings.Pricing(1.25, 10));
        assertEquals(0, unpricedCache.cacheDiscount(800_000), 1e-12);
        assertThrows(AiException.class, () -> new ModelSettings.Pricing(1.25, 10, -0.1));
        // A cache-read rate above the input rate (free input, paid cache reads) adds the difference instead of dropping it.
        var paidCache = ChatModelPricing.of(new ModelSettings.Pricing(0, 10, 0.5));
        assertEquals(-0.4, paidCache.cacheDiscount(800_000), 1e-12);
    }
}
