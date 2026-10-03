package io.memoryos.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Usage;
import com.embabel.common.ai.model.LlmMetadata;
import com.embabel.common.ai.model.PricingModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What one turn's guards add up to: known, partly known or unknown, and never invented. */
class ModelAccountingTest {
    private final AgentProcess process = mock(AgentProcess.class);
    private final LlmMetadata metadata = mock(LlmMetadata.class);

    private static ModelGuard guard(boolean used, boolean known, boolean reported, long cached) {
        var guard = mock(ModelGuard.class);
        when(guard.used()).thenReturn(used);
        when(guard.usageKnown()).thenReturn(known);
        when(guard.usageReported()).thenReturn(reported);
        when(guard.cacheReadTokens()).thenReturn(cached);
        return guard;
    }

    private void spent(int input, int output, double cost) {
        when(process.usage()).thenReturn(new Usage(input, output, null));
        when(process.cost()).thenReturn(cost);
        when(metadata.getPricingModel()).thenReturn(mock(PricingModel.class));
    }

    @Test
    void everyCallReportedIsCompleteWithItsCost() {
        spent(1000, 100, 0.00042);
        var accounting = ModelAccounting.of(List.of(guard(true, true, true, 0)), process, metadata);
        assertEquals(1000L, accounting.input());
        assertEquals(100L, accounting.output());
        assertEquals(0.00042, accounting.cost(), 1e-12);
        assertTrue(accounting.complete());
    }

    @Test
    void aCallWithoutUsageKeepsWhatTheOthersReportedAndMarksTheTurnIncomplete() {
        // Staging, 2026-10-02: one silent call in a turn of several erased the usage of all of them.
        spent(1000, 100, 0.00042);
        var accounting = ModelAccounting.of(List.of(guard(true, true, true, 200), guard(true, false, false, 0)),
                process, metadata);
        assertEquals(1000L, accounting.input());
        assertEquals(100L, accounting.output());
        assertEquals(200L, accounting.cacheRead());
        assertFalse(accounting.complete());
        assertTrue(accounting.used());
        // The stored answer does not show partial totals as its own; only the usage ledger keeps them.
        assertNull(accounting.shown().input());
        assertNull(accounting.shown().cost());
        var whole = complete();
        assertSame(whole, whole.shown());
    }

    private ModelAccounting complete() {
        return new ModelAccounting(10L, 2L, 0.1, 0, true);
    }

    @Test
    void noCallReportingLeavesTheTotalsUnknownAndNoUseLeavesNothing() {
        var silent = ModelAccounting.of(List.of(guard(true, false, false, 0)), process, metadata);
        assertNull(silent.input());
        assertNull(silent.cost());
        assertTrue(silent.used());
        assertFalse(silent.complete());
        assertSame(ModelAccounting.NONE, ModelAccounting.of(List.of(guard(false, false, false, 0)), process, metadata));
    }
}
