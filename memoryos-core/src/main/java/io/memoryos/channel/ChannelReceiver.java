package io.memoryos.channel;

import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What a transport hands the capability: each message it reads, and, from a provider that says so, each group chat the
 * bot is added to (MEM-273), so the group is on the bot's page before anyone asks there.
 */
@FunctionalInterface
public interface ChannelReceiver extends Consumer<ChannelMessage> {
    /**
     * The bot was added to a group chat whose people all belong to its organisation; {@code name} is null when the
     * provider gives none. Returns at once.
     */
    default void joined(String chatId, @Nullable String name) {}
}
