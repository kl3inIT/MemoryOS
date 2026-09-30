package io.memoryos.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Connection checks the adapters share (Onyx validate_credentials parity). A configured endpoint must not redirect
 * the credential elsewhere, and error bodies are never read.
 */
final class VoiceChecks {
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CHECK_TIMEOUT).build();
    private static final int MAX_LISTING_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();

    private VoiceChecks() {}

    /** A bounded JSON listing whose {@code field} is an array proves the endpoint and credential. */
    static void jsonListing(String url, Map<String, String> headers, String field) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(CHECK_TIMEOUT).header("Accept", "application/json");
            headers.forEach(request::header);
            var response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw VoiceException.providerUnavailable();
                body = stream.readNBytes(MAX_LISTING_BYTES + 1);
            }
            if (body.length > MAX_LISTING_BYTES || !JSON.readTree(body).path(field).isArray())
                throw VoiceException.providerUnavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw VoiceException.providerUnavailable();
        } catch (VoiceException expected) {
            throw expected;
        } catch (IOException | RuntimeException failure) {
            throw VoiceException.providerUnavailable();
        }
    }

    /** A JSON array listing; only its start is read, because the Azure voice list is large. */
    static void arrayListing(String url, String header, String key) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(CHECK_TIMEOUT)
                    .header("Accept", "application/json").header(header, key).GET().build();
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw VoiceException.providerUnavailable();
                String start = new String(stream.readNBytes(256), StandardCharsets.UTF_8).replace("﻿", "").stripLeading();
                if (!start.startsWith("[")) throw VoiceException.providerUnavailable();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw VoiceException.providerUnavailable();
        } catch (VoiceException expected) {
            throw expected;
        } catch (IOException | RuntimeException failure) {
            throw VoiceException.providerUnavailable();
        }
    }
}
