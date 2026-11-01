package io.memoryos.channel;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A bot of the Tenant that takes a linked account (MEM-263); {@code linkedAt} is null while the member has not linked one. */
public record ChannelLink(UUID connectionId, String botName, ChannelProvider provider, @Nullable Instant linkedAt) {}
