package io.memoryos.channel;

import io.memoryos.channel.persistence.JdbcChannelInboundRepository;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Deletes the ids of messages a bot received longer ago than {@code memoryos.channel.message-retention}. Their text
 * was never here: it lives and ends with the chat session. A reply to a message this old starts a new conversation.
 */
@Service
@EnableConfigurationProperties(ChannelProperties.class)
public class ChannelMessageRetention {
    public static final int BATCH = 1000;

    private final JdbcChannelInboundRepository inbound;
    private final ChannelProperties properties;
    private final Clock clock = Clock.systemUTC();

    public ChannelMessageRetention(JdbcChannelInboundRepository inbound, ChannelProperties properties) {
        this.inbound = inbound;
        this.properties = properties;
    }

    /** Deletes at most one batch; a full batch means more may be waiting. */
    public int sweep() {
        return inbound.deleteMessagesBefore(clock.instant().minus(properties.messageRetention()), BATCH);
    }
}
