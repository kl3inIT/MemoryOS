package io.memoryos.ai.systemone;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Exactly one adapter per {@link SystemOneProvider}, checked when the application starts: a type without an adapter,
 * or with two, fails startup rather than a request.
 */
@Component
public final class SystemOneAdapterRegistry {
    private final Map<SystemOneProvider, SystemOneAdapter> adapters = new EnumMap<>(SystemOneProvider.class);

    public SystemOneAdapterRegistry(List<SystemOneAdapter> adapters) {
        for (var adapter : adapters)
            if (this.adapters.putIfAbsent(adapter.provider(), adapter) != null)
                throw new IllegalStateException("Two System One adapters for " + adapter.provider());
        for (var provider : SystemOneProvider.values())
            if (!this.adapters.containsKey(provider))
                throw new IllegalStateException("No System One adapter for " + provider);
    }

    public SystemOneCapabilities capabilities(SystemOneProvider provider) { return adapters.get(provider).capabilities(); }

    SystemOneAdapter adapter(SystemOneProvider provider) { return adapters.get(provider); }
}
