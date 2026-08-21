package io.memoryos.api.invitation;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.invitation")
public record MemoryOsInvitationProperties(Duration timeToLive) {

    public MemoryOsInvitationProperties {
        Objects.requireNonNull(timeToLive, "memoryos.invitation.time-to-live must not be null");
        if (timeToLive.compareTo(Duration.ofMinutes(5)) < 0
                || timeToLive.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException(
                    "memoryos.invitation.time-to-live must be between 5 minutes and 30 days"
            );
        }
    }
}
