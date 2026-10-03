package io.memoryos.voice;

import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Connection checks the adapters share (Onyx validate_credentials parity). A configured endpoint must not redirect
 * the credential elsewhere, and error bodies are never read.
 */
final class VoiceChecks {
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(15);
    private static final RestClient LISTING = OutboundHttp.builder(new Limits(CHECK_TIMEOUT, 1_048_576)).build();
    /** For a list of any size: the check reads its first bytes and closes the response. */
    private static final RestClient START = OutboundHttp.builder(new Limits(CHECK_TIMEOUT, Integer.MAX_VALUE)).build();
    private static final int START_BYTES = 256;

    private VoiceChecks() {}

    /** A bounded JSON listing whose {@code field} is an array proves the endpoint and credential. */
    static void jsonListing(String url, Map<String, String> headers, String field) {
        try {
            JsonNode listing = LISTING.get().uri(URI.create(url)).accept(MediaType.APPLICATION_JSON)
                    .headers(sent -> headers.forEach(sent::set)).retrieve().body(JsonNode.class);
            if (listing == null || !listing.path(field).isArray()) throw VoiceException.providerUnavailable();
        } catch (RestClientException failure) {
            throw VoiceException.providerUnavailable();
        }
    }

    /** A JSON array listing; only its start is read, because the Azure voice list is large. */
    static void arrayListing(String url, String header, String key) {
        try {
            String start = START.get().uri(URI.create(url)).accept(MediaType.APPLICATION_JSON).header(header, key)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw VoiceException.providerUnavailable();
                        return new String(response.getBody().readNBytes(START_BYTES), StandardCharsets.UTF_8);
                    });
            if (!start.replace("﻿", "").stripLeading().startsWith("[")) throw VoiceException.providerUnavailable();
        } catch (RestClientException failure) {
            throw VoiceException.providerUnavailable();
        }
    }
}
