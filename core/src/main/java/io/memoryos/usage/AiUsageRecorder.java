package io.memoryos.usage;

import io.memoryos.usage.persistence.AiUsageRepository;
import org.springframework.stereotype.Component;

/**
 * Adds AI calls to the daily rollup. Writes join the caller's transaction, so a chat turn's usage commits with the
 * turn itself; unlike Onyx's buffered processor nothing is dropped, because limits are enforced on these totals.
 */
@Component
public class AiUsageRecorder {
    private final AiUsageRepository usage;

    public AiUsageRecorder(AiUsageRepository usage) { this.usage = usage; }

    public void record(AiUsage call) { usage.add(call); }
}
