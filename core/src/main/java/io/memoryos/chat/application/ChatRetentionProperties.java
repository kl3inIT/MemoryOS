package io.memoryos.chat.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One deployment switch, as Onyx's {@code HARD_DELETE_CHATS}: with it off a deleted conversation keeps its
 * rows and its generated files forever, and with it on a worker purges what the owner already deleted.
 */
@ConfigurationProperties("memoryos.chat.retention")
public record ChatRetentionProperties(@DefaultValue("false") boolean hardDelete) {
    /** Conversations claimed per tick; a larger batch would hold locks on more rows at once for no gain. */
    public int batchSize() { return 20; }
}
