package io.memoryos.chat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.chat.web.adapter.BraveWebSearchAdapter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class WebAdapterRegistryTest {
    private final WebAdapterRegistry registry = new WebAdapterRegistry(WebClients.adapters());

    @Test void everyProviderHasExactlyOneAdapterOrStartupFails() {
        var missing = new ArrayList<>(WebClients.adapters());
        missing.removeIf(adapter -> adapter.provider() == WebProvider.FIRECRAWL);
        assertEquals("No Web adapter for FIRECRAWL",
                assertThrows(IllegalStateException.class, () -> new WebAdapterRegistry(missing)).getMessage());

        var duplicate = new ArrayList<>(WebClients.adapters());
        duplicate.add(new BraveWebSearchAdapter());
        assertEquals("Two Web adapters for BRAVE",
                assertThrows(IllegalStateException.class, () -> new WebAdapterRegistry(duplicate)).getMessage());
    }

    @Test void functionsAndCapabilitiesMatchTheImplementedProtocols() {
        assertEquals(EnumSet.complementOf(EnumSet.of(WebProvider.FIRECRAWL)), matching(registry::searches));
        assertEquals(EnumSet.of(WebProvider.TAVILY, WebProvider.EXA, WebProvider.FIRECRAWL), matching(registry::reads));
        assertEquals(EnumSet.complementOf(EnumSet.of(WebProvider.SEARXNG)), matching(p -> registry.capabilities(p).requiresKey()));
        assertEquals(EnumSet.of(WebProvider.GOOGLE_PSE, WebProvider.NINEROUTER), matching(p -> registry.capabilities(p).requiresEngine()));
        assertEquals(EnumSet.of(WebProvider.SEARXNG, WebProvider.NINEROUTER), matching(p -> registry.capabilities(p).requiresEndpoint()));
        assertEquals(EnumSet.of(WebProvider.BRAVE, WebProvider.TAVILY, WebProvider.SERPER, WebProvider.GOOGLE_PSE, WebProvider.SEARXNG),
                matching(p -> registry.capabilities(p).siteFilter()));
        assertEquals(EnumSet.of(WebProvider.NINEROUTER), matching(p -> registry.engines(p).isPresent()));
    }

    private static Set<WebProvider> matching(Predicate<WebProvider> test) {
        var providers = EnumSet.noneOf(WebProvider.class);
        for (var provider : WebProvider.values()) if (test.test(provider)) providers.add(provider);
        return providers;
    }
}
