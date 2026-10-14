package io.memoryos.channel;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param conversationIdle how long after its last message a direct message still continues the same conversation
 * @param identityTtl how long the account found for a sender is used before the provider is asked again
 * @param messageRetention how long the ids of a received message are kept
 * @param senderRate how many messages one person may send a bot in a minute; more are refused and told so
 * @param orderWait how long a message waits for the turn its conversation is already running
 */
@ConfigurationProperties("memoryos.channel")
public record ChannelProperties(@DefaultValue("1h") Duration conversationIdle, @DefaultValue("1h") Duration identityTtl,
                                @DefaultValue("90d") Duration messageRetention, @DefaultValue("10") int senderRate,
                                @DefaultValue("5m") Duration orderWait) {
    public ChannelProperties {
        if (conversationIdle.isNegative() || conversationIdle.isZero() || identityTtl.isNegative()
                || messageRetention.compareTo(Duration.ofDays(1)) < 0 || senderRate < 1 || orderWait.isNegative()) {
            throw new IllegalArgumentException("Invalid channel configuration");
        }
    }
}
