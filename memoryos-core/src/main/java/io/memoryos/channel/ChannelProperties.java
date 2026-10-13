package io.memoryos.channel;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param conversationIdle how long after its last message a direct message still continues the same conversation
 * @param identityTtl how long the account found for a sender is used before the provider is asked again
 * @param messageRetention how long the ids of a received message are kept
 */
@ConfigurationProperties("memoryos.channel")
public record ChannelProperties(@DefaultValue("1h") Duration conversationIdle, @DefaultValue("1h") Duration identityTtl,
                                @DefaultValue("90d") Duration messageRetention) {
    public ChannelProperties {
        if (conversationIdle.isNegative() || conversationIdle.isZero() || identityTtl.isNegative()
                || messageRetention.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalArgumentException("Invalid channel configuration");
        }
    }
}
