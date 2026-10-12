package io.memoryos.voice;

import java.util.List;

/**
 * What a speech provider needs, and the identifiers it suggests. Model and voice lists are suggestions; administrators
 * may enter any identifier the provider accepts. Which functions it offers follows from its adapter.
 *
 * @param defaultEndpoint  the public API used when a connection configures none; empty when the manager must supply one
 * @param requiresKey      a connection is unusable without a stored credential
 * @param requiresEndpoint the endpoint is the whole address of a self-hosted or resource deployment
 */
public record VoiceProviderCapabilities(String defaultEndpoint, boolean requiresKey, boolean requiresEndpoint,
                                        List<String> sttModels, List<String> ttsModels, List<String> voices) {
    public VoiceProviderCapabilities {
        sttModels = List.copyOf(sttModels);
        ttsModels = List.copyOf(ttsModels);
        voices = List.copyOf(voices);
    }

    /** The configured endpoint, or the provider's public API when none is configured, without trailing slashes. */
    public String baseUrl(String endpoint) {
        return (endpoint.isEmpty() ? defaultEndpoint : endpoint).replaceAll("/+$", "");
    }
}
