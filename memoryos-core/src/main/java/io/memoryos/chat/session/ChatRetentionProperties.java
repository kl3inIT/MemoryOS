package io.memoryos.chat.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention for Chat. {@code hardDelete} is Onyx's {@code HARD_DELETE_CHATS} switch: with it off a deleted
 * conversation keeps its rows and its generated files forever, and with it on a worker purges what the owner
 * already deleted.
 *
 * <p>The file trash window under the same prefix, {@code trash-after}, is the library's
 * ({@link io.memoryos.library.LibraryTrashProperties}). {@code deletedAfter} is how long a deleted conversation waits before the purge takes it
 * (MEM-153), which is ChatGPT's thirty days, and {@code temporaryAfter} is how long a temporary conversation
 * survives its last message. {@code 0} means at once in every case, which is how each behaved before its
 * window existed. None of these windows is visible to anyone: a deleted conversation is gone from the
 * interface the moment it is deleted.
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
