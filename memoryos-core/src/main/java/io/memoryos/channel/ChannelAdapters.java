package io.memoryos.channel;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The adapter of each provider this process has; a provider without one here has no bot here. */
@Component
public class ChannelAdapters {
    private final Map<ChannelProvider, ChannelAdapter> adapters = new EnumMap<>(ChannelProvider.class);

    public ChannelAdapters(List<ChannelAdapter> adapters) {
        for (var adapter : adapters) {
            if (this.adapters.put(adapter.provider(), adapter) != null) {
                throw new IllegalStateException("two channel adapters for " + adapter.provider());
            }
        }
    }

    public ChannelAdapter of(ChannelProvider provider) {
        var adapter = adapters.get(provider);
        if (adapter == null) throw new IllegalStateException("no channel adapter for " + provider);
        return adapter;
    }
}
