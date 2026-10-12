package io.memoryos.chat.web;

import org.springframework.modulith.NamedInterface;

/**
 * The implemented external Web protocols, persisted and in the API. Identity only: what each needs and supports comes
 * from its adapter through {@link WebAdapterRegistry}. Native model tools are not search-engine connections.
 */
@NamedInterface("web")
public enum WebProvider {
    BRAVE, TAVILY, EXA, SERPER, GOOGLE_PSE, SEARXNG, NINEROUTER, FIRECRAWL
}
