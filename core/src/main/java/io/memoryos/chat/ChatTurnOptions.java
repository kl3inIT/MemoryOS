package io.memoryos.chat;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Persona restrictions resolved for one turn; empty sourceIds means all currently authorized sources. */
public record ChatTurnOptions(boolean searchEnabled, List<UUID> sourceIds,
                              @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit) {
    public static final ChatTurnOptions DEFAULT = new ChatTurnOptions(true, List.of(), null, null);
    public ChatTurnOptions { sourceIds = List.copyOf(sourceIds); }
}
