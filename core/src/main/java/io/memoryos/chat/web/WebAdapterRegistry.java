package io.memoryos.chat.web;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Exactly one adapter per {@link WebProvider}, checked when the application starts: a provider without an adapter, or
 * with two, fails startup rather than a request.
 */
@Component
public final class WebAdapterRegistry {
    private final Map<WebProvider, WebAdapter> adapters = new EnumMap<>(WebProvider.class);

    public WebAdapterRegistry(List<WebAdapter> adapters) {
        for (var adapter : adapters)
            if (this.adapters.putIfAbsent(adapter.provider(), adapter) != null)
                throw new IllegalStateException("Two Web adapters for " + adapter.provider());
        for (var provider : WebProvider.values())
            if (!this.adapters.containsKey(provider)) throw new IllegalStateException("No Web adapter for " + provider);
    }

    public WebProviderCapabilities capabilities(WebProvider provider) { return adapters.get(provider).capabilities(); }
    public boolean searches(WebProvider provider) { return adapters.get(provider) instanceof WebSearchAdapter; }
    public boolean reads(WebProvider provider) { return adapters.get(provider) instanceof WebContentAdapter; }

    Optional<WebSearchAdapter> search(WebProvider provider) {
        return adapters.get(provider) instanceof WebSearchAdapter search ? Optional.of(search) : Optional.empty();
    }
    Optional<WebContentAdapter> content(WebProvider provider) {
        return adapters.get(provider) instanceof WebContentAdapter content ? Optional.of(content) : Optional.empty();
    }
    Optional<WebEngineListAdapter> engines(WebProvider provider) {
        return adapters.get(provider) instanceof WebEngineListAdapter engines ? Optional.of(engines) : Optional.empty();
    }
}
