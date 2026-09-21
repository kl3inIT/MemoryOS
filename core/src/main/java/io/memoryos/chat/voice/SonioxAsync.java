package io.memoryos.chat.voice;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.memoryos.chat.ChatException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Soniox async transcription of one recording: upload the file, create a transcription, poll until it completes, read
 * the transcript, and always delete both the transcription and the file (Anarlog {@code soniox/batch.rs}).
 */
final class SonioxAsync {
    static final String DEFAULT_MODEL = "stt-async-v5";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final ObjectMapper JSON = new ObjectMapper();

    private SonioxAsync() {}

    /** Transcribes a WAV recording and returns its text; the language is an ISO-639-1 hint such as {@code vi}. */
    static String transcribe(HttpClient client, String baseUrl, String key, String model, @Nullable String language,
            byte[] wav, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String file = null;
        String transcription = null;
        try {
            file = upload(client, baseUrl, key, wav, timeout).path("id").asString("");
            if (file.isEmpty()) throw ChatException.providerUnavailable();
            var body = new LinkedHashMap<String, Object>();
            body.put("model", asyncModel(model));
            body.put("file_id", file);
            if (language != null) {
                body.put("language_hints", List.of(language));
                body.put("language_hints_strict", true);
            }
            transcription = send(client, post(baseUrl + "/transcriptions", key, JSON.writeValueAsString(body), timeout))
                    .path("id").asString("");
            if (transcription.isEmpty()) throw ChatException.providerUnavailable();
            while (true) {
                String status = send(client, get(baseUrl + "/transcriptions/" + encode(transcription), key, timeout))
                        .path("status").asString("");
                if ("completed".equals(status)) break;
                if ("error".equals(status) || "failed".equals(status) || System.nanoTime() > deadline)
                    throw ChatException.providerUnavailable();
                Thread.sleep(POLL_INTERVAL);
            }
            return send(client, get(baseUrl + "/transcriptions/" + encode(transcription) + "/transcript", key, timeout))
                    .path("text").asString("").strip();
        } finally {
            if (transcription != null && !transcription.isEmpty())
                delete(client, baseUrl + "/transcriptions/" + encode(transcription), key, timeout);
            if (file != null && !file.isEmpty()) delete(client, baseUrl + "/files/" + encode(file), key, timeout);
        }
    }

    /** The connection holds the realtime model; its async sibling has the same version ({@code stt-rt-v5} → {@code stt-async-v5}). */
    static String asyncModel(String model) {
        if (model.startsWith("stt-async-")) return model;
        if (model.startsWith("stt-rt-")) return "stt-async-" + model.substring("stt-rt-".length());
        return DEFAULT_MODEL;
    }

    private static JsonNode upload(HttpClient client, String baseUrl, String key, byte[] wav, Duration timeout)
            throws IOException, InterruptedException {
        String boundary = "memoryos-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream(wav.length + 256);
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(UTF_8));
        body.writeBytes(wav);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(UTF_8));
        return send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/files")).timeout(timeout)
                .header("Authorization", "Bearer " + key).header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build());
    }

    private static HttpRequest post(String url, String key, String json, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Authorization", "Bearer " + key)
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private static HttpRequest get(String url, String key, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Authorization", "Bearer " + key)
                .header("Accept", "application/json").GET().build();
    }

    /** Provider error bodies are never read, so account detail cannot reach a response or log. */
    private static JsonNode send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw ChatException.providerUnavailable();
        return JSON.readTree(response.body());
    }

    /** Cleanup is best effort: a failed delete must not replace the transcription's own outcome. */
    private static void delete(HttpClient client, String url, String key, Duration timeout) {
        try {
            client.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Authorization", "Bearer " + key)
                    .DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
            // Soniox removes abandoned files on its own schedule.
        }
    }

    private static String encode(String id) {
        return URLEncoder.encode(id, UTF_8);
    }
}
