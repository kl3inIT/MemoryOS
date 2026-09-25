package io.memoryos.voice;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Voice provider protocol calls. Provider payloads and credentials never reach responses, logs or metrics. */
@Component
public class VoiceProviderClient {
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(15);
    /** One client for every check; a configured endpoint must not redirect the credential elsewhere. */
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CHECK_TIMEOUT).build();
    private static final int MAX_MODEL_LIST_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Semaphore checks = new Semaphore(2);
    private final MeterRegistry meters;

    public VoiceProviderClient(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * Onyx validate_credentials parity: an authorized listing proves the endpoint and credential (OpenAI-protocol and
     * ElevenLabs models, Azure voices). It does not certify that the configured models or voices exist.
     */
    public void verify(VoiceConnectionService.Probe probe) {
        if (!checks.tryAcquire()) throw VoiceException.busy();
        long start = System.nanoTime();
        String outcome = "failed";
        try {
            switch (probe.provider()) {
                case OPENAI, OPENAI_COMPATIBLE -> listModels(probe);
                case ELEVENLABS -> requireArray(probe.baseUrl() + "/models", "xi-api-key", probe.key());
                case AZURE -> requireArray(probe.baseUrl() + AzureSpeech.VOICES_PATH, "Ocp-Apim-Subscription-Key", probe.key());
                case SONIOX -> requireSonioxListing(probe);
            }
            outcome = "succeeded";
        } finally {
            checks.release();
            // Bounded dimensions only; checks are not a billing estimate.
            meters.timer("memoryos.chat.voice.request", "provider", probe.provider().name(), "operation", "verify",
                    "outcome", outcome).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private static void listModels(VoiceConnectionService.Probe probe) {
        // A configured endpoint must not redirect the credential elsewhere; error bodies are never read.
        try {
            var request = HttpRequest.newBuilder(URI.create(probe.baseUrl() + "/models")).timeout(CHECK_TIMEOUT)
                    .header("Accept", "application/json");
            if (!probe.key().isEmpty()) request.header("Authorization", "Bearer " + probe.key());
            var response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw VoiceException.providerUnavailable();
                body = stream.readNBytes(MAX_MODEL_LIST_BYTES + 1);
            }
            if (body.length > MAX_MODEL_LIST_BYTES || !JSON.readTree(body).path("data").isArray())
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

    /** Soniox has no model listing; an authorized one-item transcription listing proves the key (Anarlog provider validation). */
    private static void requireSonioxListing(VoiceConnectionService.Probe probe) {
        try {
            var request = HttpRequest.newBuilder(URI.create(probe.baseUrl() + "/transcriptions?limit=1")).timeout(CHECK_TIMEOUT)
                    .header("Accept", "application/json").header("Authorization", "Bearer " + probe.key()).GET().build();
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw VoiceException.providerUnavailable();
                body = stream.readNBytes(MAX_MODEL_LIST_BYTES + 1);
            }
            if (body.length > MAX_MODEL_LIST_BYTES || !JSON.readTree(body).path("transcriptions").isArray())
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
    private static void requireArray(String url, String header, String key) {
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
