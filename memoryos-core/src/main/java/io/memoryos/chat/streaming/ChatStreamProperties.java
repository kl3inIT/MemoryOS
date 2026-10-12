package io.memoryos.chat.streaming;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Replay limits follow Onyx: a live buffer expires only after {@code ttl} without a write, a completed one is kept for
 * {@code doneTtl}, one reply holds at most {@code runBytes}, and a reader polls Redis every {@code pollInterval}.
 * Readers are bounded per process because each holds a virtual thread and an HTTP response.
 */
@ConfigurationProperties("memoryos.chat.stream")
public record ChatStreamProperties(@DefaultValue("16777216") int runBytes, @DefaultValue("60m") Duration ttl,
                                   @DefaultValue("10m") Duration doneTtl,
                                   @DefaultValue("16384") int chunkBytes, @DefaultValue("25ms") Duration flushInterval,
                                   @DefaultValue("4") int readersPerRun, @DefaultValue("64") int maxReaders,
                                   @DefaultValue("262144") int readBytes, @DefaultValue("200ms") Duration pollInterval,
                                   @DefaultValue("15s") Duration heartbeat, @DefaultValue("10m") Duration connectionTimeout) {
    public ChatStreamProperties {
        if (runBytes < 512 || chunkBytes < 4 || chunkBytes + 256L > runBytes || readBytes < chunkBytes + 256L
                || readersPerRun < 1 || maxReaders < readersPerRun || invalid(ttl) || invalid(doneTtl) || invalid(flushInterval)
                || invalid(pollInterval) || invalid(heartbeat) || invalid(connectionTimeout))
            throw new IllegalArgumentException("Invalid Chat stream limits");
    }

    private static boolean invalid(Duration value) {
        return value == null || value.compareTo(Duration.ofDays(1)) > 0 || value.toMillis() <= 0;
    }
}
