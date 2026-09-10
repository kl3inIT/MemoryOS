package io.memoryos.chat.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("memoryos.chat.search")
public record ChatSearchProperties(@DefaultValue("30") int candidates, @DefaultValue("10") int sections,
                                   @DefaultValue("6000") int selectionTokens, @DefaultValue("8000") int contextTokens,
                                   @DefaultValue("3") int maxCalls, @DefaultValue("60s") Duration helperTimeout,
                                   @DefaultValue("true") boolean autoDetectFilters,
                                   @DefaultValue("1s") Duration cleanupTimeout) {
    public ChatSearchProperties {
        if (candidates > 30 || sections < 1 || sections > 10 || sections > candidates
                || selectionTokens < 512 || selectionTokens > 16000 || contextTokens < 512 || contextTokens > 32000
            || maxCalls < 1 || maxCalls > 6 || helperTimeout == null || helperTimeout.isNegative() || helperTimeout.isZero()
            || helperTimeout.compareTo(Duration.ofMinutes(2)) > 0 || cleanupTimeout == null || cleanupTimeout.isNegative()
            || cleanupTimeout.isZero() || cleanupTimeout.compareTo(Duration.ofSeconds(5)) > 0)
            throw new IllegalArgumentException("Invalid Chat search limits");
    }

    /** Two rewrites, one time inference, then source/selection/classification; two native binding attempts. */
    public int helperCallLimit() { return 2 * (3 + maxCalls * (2 + sections)); }
}
