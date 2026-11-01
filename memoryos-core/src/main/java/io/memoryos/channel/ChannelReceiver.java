package io.memoryos.channel;

import java.time.Instant;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What a transport hands the capability: each message it reads, and, from a provider that says so, each group chat the
 * bot is added to (MEM-273), so the group is on the bot's page before anyone asks there, and the code a bot being
 * linked shows (MEM-283).
 */
@FunctionalInterface
public interface ChannelReceiver extends Consumer<ChannelMessage> {
    /**
     * The bot was added to a group chat whose people all belong to its organisation; {@code name} is null when the
     * provider gives none. Returns at once.
     */
    default void joined(String chatId, @Nullable String name) {}

    /**
     * A bot of a provider that {@link ChannelFeatures#pairs pairs} shows this code until {@code expiresAt}, to be
     * scanned in the platform's app; each new code replaces the last. Returns at once.
     */
    default void pairing(String code, Instant expiresAt) {}

    /** The code was scanned by the bot's own number, or the pairing ended without a scan; no code is shown now. */
    default void paired() {}
}
