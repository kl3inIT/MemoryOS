package io.memoryos.channel;

import java.util.Map;
import java.util.Optional;

/**
 * Where Lark hosts a bot's app: Lark international or Feishu, whose addresses differ. Kept in the connection's
 * settings under {@link #SETTING}.
 */
public enum ChannelRegion {
    LARK,
    FEISHU;

    public static final String SETTING = "region";

    /** The region a connection's settings name; empty when they name none or an unknown one. */
    public static Optional<ChannelRegion> of(Map<String, String> settings) {
        String value = settings.get(SETTING);
        if (value == null) return Optional.empty();
        for (var region : values()) {
            if (region.name().equals(value)) return Optional.of(region);
        }
        return Optional.empty();
    }
}
