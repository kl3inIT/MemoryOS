package io.memoryos.chat.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/**
 * Onyx search sizes: {@code NUM_RETURNED_HITS} 50 candidates, at most 10 selected sections, and a selection budget of
 * {@code MAX_CHUNKS_FED_TO_CHAT} 25 x 512-token chunks x {@code SELECTION_TOKEN_BUDGET_MULTIPLIER} 2. As Onyx, returned
 * evidence is bounded by chunk count, not tokens: at most {@code sections} sections, each widened by at most five
 * chunks ({@code FULL_DOC_NUM_CHUNKS_AROUND}), within the context the model has left. There is no per-turn
 * search-call count: as Onyx, {@code MAX_LLM_CYCLES} bounds it.
 */
@ConfigurationProperties("memoryos.chat.search")
public record ChatSearchProperties(@DefaultValue("50") int candidates, @DefaultValue("10") int sections,
                                   @DefaultValue("25600") int selectionTokens,
                                   @DefaultValue("60s") Duration helperTimeout,
                                   @DefaultValue("true") boolean autoDetectFilters,
                                   @DefaultValue("1s") Duration cleanupTimeout) {
    public ChatSearchProperties {
        if (candidates > 50 || sections < 1 || sections > 10 || sections > candidates
                || selectionTokens < 512 || selectionTokens > 64000
            || helperTimeout == null || helperTimeout.isNegative() || helperTimeout.isZero()
            || helperTimeout.compareTo(Duration.ofMinutes(2)) > 0 || cleanupTimeout == null || cleanupTimeout.isNegative()
            || cleanupTimeout.isZero() || cleanupTimeout.compareTo(Duration.ofSeconds(5)) > 0)
            throw new IllegalArgumentException("Invalid Chat search limits");
    }

}
