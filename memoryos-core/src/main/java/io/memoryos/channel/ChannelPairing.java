package io.memoryos.channel;

import java.time.Instant;
import java.util.Objects;

/** The code a bot being linked shows (MEM-283), to be scanned in the platform's app before {@code expiresAt}. */
public record ChannelPairing(String code, Instant expiresAt) {
    public ChannelPairing {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "ChannelPairing[code=redacted, expiresAt=" + expiresAt + "]";
    }
}
