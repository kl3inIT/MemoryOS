package io.memoryos.connector.sync;

import io.memoryos.connector.SourceType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Exactly one selection adapter per external {@link SourceType}, checked when the application starts: a provider
 * without an adapter, or with two, fails startup rather than a verification.
 */
@Component
public final class SourceSelectionAdapterRegistry {
    private final Map<SourceType, SourceSelectionAdapter> adapters = new EnumMap<>(SourceType.class);

    public SourceSelectionAdapterRegistry(List<SourceSelectionAdapter> adapters) {
        for (var adapter : adapters)
            if (this.adapters.putIfAbsent(adapter.type(), adapter) != null)
                throw new IllegalStateException("Two selection adapters for " + adapter.type());
        for (var type : SourceType.values())
            if (type.external() != this.adapters.containsKey(type))
                throw new IllegalStateException(type.external()
                        ? "No selection adapter for " + type : "A selection adapter for " + type);
    }

    SourceSelectionAdapter require(SourceType type) {
        var adapter = adapters.get(type);
        if (adapter == null) throw new IllegalStateException("No selection verification for " + type);
        return adapter;
    }
}
