package io.memoryos.chat.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention for Chat. {@code hardDelete} follows ChatGPT, which removes a deleted conversation from its systems
 * within thirty days and offers nobody a switch to keep it: on by default here, and Onyx's
 * {@code HARD_DELETE_CHATS=off} behaviour — a deleted conversation keeping its rows and its generated files —
 * is what turning it off restores. A temporary conversation is purged either way, because it was promised to
 * leave no history.
 *
 * <p>{@code trashAfter} is how long a deleted file stays in the library's trash before its bytes are released
 * (MEM-152 phase 4). {@code deletedAfter} is how long a deleted conversation waits before the purge takes it
 * (MEM-153), which is ChatGPT's thirty days, and {@code temporaryAfter} is how long a temporary conversation
 * survives its last message. {@code 0} means at once in every case, which is how each behaved before its
 * window existed. None of these windows is visible to anyone: a deleted conversation is gone from the
 * interface the moment it is deleted.
 */
@ConfigurationProperties("memoryos.chat.retention")
public record ChatRetentionProperties(@DefaultValue("true") boolean hardDelete,
                                      @DefaultValue("30d") Duration trashAfter,
                                      @DefaultValue("30d") Duration deletedAfter,
                                      @DefaultValue("24h") Duration temporaryAfter) {
    public ChatRetentionProperties {
        if (trashAfter == null || trashAfter.isNegative() || trashAfter.toDays() > 365) {
            throw new IllegalArgumentException("The file trash window must be between 0 and 365 days");
        }
        if (deletedAfter == null || deletedAfter.isNegative())
            throw new IllegalArgumentException("memoryos.chat.retention.deleted-after must not be negative");
        if (temporaryAfter == null || temporaryAfter.isNegative())
            throw new IllegalArgumentException("memoryos.chat.retention.temporary-after must not be negative");
    }

    /** Conversations claimed per tick; a larger batch would hold locks on more rows at once for no gain. */
    public int batchSize() { return 20; }
}
