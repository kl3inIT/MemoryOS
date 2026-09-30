package io.memoryos.connector.sync;

import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceType;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Exactly one synchronization adapter per external {@link SourceType}, checked when the application starts: a
 * provider without an adapter, or with two, fails startup rather than a run. Uploaded files have no provider and
 * no adapter.
 */
@Component
public final class SourceSyncAdapterRegistry {
    private final Map<SourceType, SourceSyncAdapter> adapters = new EnumMap<>(SourceType.class);

    public SourceSyncAdapterRegistry(List<SourceSyncAdapter> adapters) {
        for (var adapter : adapters)
            if (this.adapters.putIfAbsent(adapter.type(), adapter) != null)
                throw new IllegalStateException("Two synchronization adapters for " + adapter.type());
        for (var type : SourceType.values())
            if (type.external() != this.adapters.containsKey(type))
                throw new IllegalStateException(type.external()
                        ? "No synchronization adapter for " + type : "A synchronization adapter for " + type);
    }

    Collection<SourceSyncAdapter> all() {
        return adapters.values();
    }

    SourceSyncAdapter require(SourceType type) {
        var adapter = adapters.get(type);
        if (adapter == null) throw new IllegalStateException("No synchronization for " + type);
        return adapter;
    }

    /** Whether Sources of the type may admit readers by the provider's own sharing settings. */
    public boolean permissionSync(SourceType type) {
        var adapter = adapters.get(type);
        return adapter != null && adapter.capabilities().permissionSync();
    }

    /** Inside the transaction that resumed a paused Source. */
    public void resumed(SourceType type, TenantId tenant, SourceId source, ActorId actor) {
        var adapter = adapters.get(type);
        if (adapter != null) adapter.resumed(tenant, source, actor);
    }

    /** Inside the transaction that removes one item from a Source. */
    public void itemRemoved(SourceType type, TenantId tenant, SourceId source, SourceItemId item) {
        var adapter = adapters.get(type);
        if (adapter != null) adapter.itemRemoved(tenant, source, item);
    }

    /**
     * Inside a transaction: whether the Source still owns the usable credential revision a stored version
     * captured. A type without a provider has no such authority and fails closed.
     */
    public boolean credentialCurrent(SourceType type, TenantId tenant, SourceId source, long credentialRevision) {
        var adapter = adapters.get(type);
        return adapter != null && adapter.credentialCurrent(tenant, source, credentialRevision);
    }
}
