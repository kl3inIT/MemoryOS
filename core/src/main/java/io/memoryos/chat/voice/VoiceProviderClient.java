package io.memoryos.chat.voice;

import io.memoryos.chat.ChatException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Voice provider protocol calls. Provider payloads and credentials never reach responses, logs or metrics. */
@Component
public class VoiceProviderClient {
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_MODEL_LIST_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Semaphore checks = new Semaphore(2);
    private final MeterRegistry meters;

    public VoiceProviderClient(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * Onyx validate_credentials parity: an authorized OpenAI-protocol model listing proves the endpoint and credential.
     * It does not certify that the configured models or voices exist.
     */
    public void verify(VoiceConnectionService.Probe probe) {
        if (!checks.tryAcquire()) throw ChatException.busy();
        long start = System.nanoTime();
        String outcome = "failed";
        try {
            listModels(probe);
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
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CHECK_TIMEOUT).build()) {
            var request = HttpRequest.newBuilder(URI.create(probe.baseUrl() + "/models")).timeout(CHECK_TIMEOUT)
                    .header("Accept", "application/json");
            if (!probe.key().isEmpty()) request.header("Authorization", "Bearer " + probe.key());
            var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw ChatException.providerUnavailable();
                body = stream.readNBytes(MAX_MODEL_LIST_BYTES + 1);
            }
            if (body.length > MAX_MODEL_LIST_BYTES || !JSON.readTree(body).path("data").isArray())
                throw ChatException.providerUnavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ChatException.providerUnavailable();
        } catch (ChatException expected) {
            throw expected;
        } catch (IOException | RuntimeException failure) {
            throw ChatException.providerUnavailable();
        }
    }
}
