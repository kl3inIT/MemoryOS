package io.memoryos.chat.web;

/**
 * One Web provider's protocol. A class implements {@link WebSearchAdapter}, {@link WebContentAdapter}, or both, and
 * {@link WebAdapterRegistry} holds exactly one class per {@link WebProvider}.
 */
public interface WebAdapter {
    WebProvider provider();
    WebProviderCapabilities capabilities();
}
