package io.memoryos.channel;

import java.util.Map;
import java.util.Optional;

/**
 * How a provider that offers both hands a bot its messages, as the administrator chose for the bot (MEM-263): it
 * pushes them to MemoryOS's public address, or MemoryOS asks for them, for a host the provider cannot call. Kept in
 * the connection's settings under {@link #SETTING}; a provider with one way of its own (Lark's connection) has none.
 */
public enum ChannelDelivery {
    WEBHOOK,
    POLLING;

    public static final String SETTING = "transport";

    /** The delivery a connection's settings name; empty when they name none or an unknown one. */
    public static Optional<ChannelDelivery> of(Map<String, String> settings) {
        String value = settings.get(SETTING);
        if (value == null) return Optional.empty();
        for (var delivery : values()) {
            if (delivery.name().equals(value)) return Optional.of(delivery);
        }
        return Optional.empty();
    }
}
