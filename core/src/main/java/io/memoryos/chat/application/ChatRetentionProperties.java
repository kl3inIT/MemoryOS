package io.memoryos.chat.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention for Chat: the {@code HARD_DELETE_CHATS} switch for conversations their owner deleted, and how long
 * a deleted file stays in the file library's trash before its bytes are released ({@code 0} releases at once,
 * which is how the library behaved before MEM-152 phase 4).
 */
@ConfigurationProperties("memoryos.chat.retention")
public record ChatRetentionProperties(@DefaultValue("false") boolean hardDelete,
                                      @DefaultValue("30d") java.time.Duration trashAfter) {
    public ChatRetentionProperties {
        if (trashAfter.isNegative() || trashAfter.toDays() > 365) {
            throw new IllegalArgumentException("The file trash window must be between 0 and 365 days");
        }
    }

    /** Conversations claimed per tick; a larger batch would hold locks on more rows at once for no gain. */
    public int batchSize() { return 20; }
}
