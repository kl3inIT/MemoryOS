package io.memoryos.channel;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a bot's platform needs to know its app, in the provider's own terms: the provider's id of the app when the
 * administrator gives one, the app's secret, and the provider's public settings (Lark's region). The adapter checks
 * and normalises them; the secret is kept encrypted and the settings as they are.
 */
public record ChannelCredentials(@Nullable String appId, String secret, Map<String, String> settings) {
    public ChannelCredentials {
        Objects.requireNonNull(secret, "secret");
        settings = Map.copyOf(settings);
    }

    @Override
    public @NonNull String toString() {
        return "ChannelCredentials[appId=" + appId + ", secret=redacted, settings=" + settings + "]";
    }
}
