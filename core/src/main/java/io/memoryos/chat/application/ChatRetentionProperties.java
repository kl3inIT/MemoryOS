package io.memoryos.chat.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One deployment switch, as Onyx's {@code HARD_DELETE_CHATS}: with it off a deleted conversation keeps its
 * rows and its generated files forever, and with it on a worker purges what the owner already deleted.
 *
 * <p>{@code deletedAfter} is how long a deleted conversation waits before that purge takes it (MEM-153), which
 * is ChatGPT's thirty days; {@code 0} purges at once, which is how MEM-143 behaved. {@code temporaryAfter} is
 * how long a temporary conversation survives its last message. Neither window is visible to anyone: a deleted
 * conversation is gone from the interface the moment it is deleted.
 */
@ConfigurationProperties("memoryos.chat.retention")
public record ChatRetentionProperties(@DefaultValue("false") boolean hardDelete,
                                      @DefaultValue("30d") Duration deletedAfter,
                                      @DefaultValue("24h") Duration temporaryAfter) {
    public ChatRetentionProperties {
        if (deletedAfter == null || deletedAfter.isNegative())
            throw new IllegalArgumentException("memoryos.chat.retention.deleted-after must not be negative");
        if (temporaryAfter == null || temporaryAfter.isNegative())
            throw new IllegalArgumentException("memoryos.chat.retention.temporary-after must not be negative");
    }

    /** Conversations claimed per tick; a larger batch would hold locks on more rows at once for no gain. */
    public int batchSize() { return 20; }
}
