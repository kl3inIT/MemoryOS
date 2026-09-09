package io.memoryos.chat.streaming;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("memoryos.chat.stream")
public record ChatStreamProperties(@DefaultValue("4194304") int runBytes,
                                   @DefaultValue("67108864") long totalBytes, @DefaultValue("10m") Duration ttl,
                                   @DefaultValue("16384") int chunkBytes, @DefaultValue("25ms") Duration flushInterval,
                                   @DefaultValue("131072") int readerBytes, @DefaultValue("4") int readersPerRun,
                                   @DefaultValue("64") int maxReaders, @DefaultValue("262144") int readBytes,
                                   @DefaultValue("4096") int maxStreams, @DefaultValue("15s") Duration heartbeat,
                                   @DefaultValue("10m") Duration connectionTimeout) {
    public ChatStreamProperties {
        if (runBytes < 512 || totalBytes < runBytes || chunkBytes < 4 || chunkBytes + 256L > runBytes
                || readerBytes < chunkBytes + 256L || readBytes < chunkBytes + 256L || readersPerRun < 1
                || maxReaders < readersPerRun || maxStreams < 1 || invalid(ttl) || invalid(flushInterval)
                || invalid(heartbeat) || invalid(connectionTimeout))
            throw new IllegalArgumentException("Invalid Chat stream limits");
    }

    private static boolean invalid(Duration value) {
        return value == null || value.compareTo(Duration.ofDays(1)) > 0 || value.toMillis() <= 0;
    }
}
