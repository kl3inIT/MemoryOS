package io.memoryos.chat.voice;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.memoryos.chat.ChatException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Soniox async transcription of one recording: upload the file, create a transcription, poll until it completes, read
 * the transcript, and always delete both the transcription and the file (Anarlog {@code soniox/batch.rs}).
 *
 * <p>Dictation reads the transcript's text. A meeting recording reads its tokens instead, so the speakers Soniox
 * separated and the time each sentence was said survive into the transcript the owner reads.
 */
final class SonioxAsync {
    static final String DEFAULT_MODEL = "stt-async-v5";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    /** A sentence ends at a speaker change or a pause; without one it would run for the whole recording. */
    private static final long SEGMENT_GAP_MS = 800;
    private static final int MAX_SEGMENT_CHARS = 400;
    private static final ObjectMapper JSON = new ObjectMapper();

    private SonioxAsync() {}

    /** Transcribes a WAV recording and returns its text; the language is an ISO-639-1 hint such as {@code vi}. */
    static String transcribe(HttpClient client, String baseUrl, String key, String model, @Nullable String language,
            byte[] wav, Duration timeout) throws IOException, InterruptedException {
        return run(client, baseUrl, key, model, language, List.of(), false, wav, "audio.wav", "audio/wav", timeout,
                transcript -> transcript.path("text").asString("").strip());
    }

    /** Transcribes one uploaded recording into the segments a meeting stores, separating speakers when asked. */
    static List<LiveTranscription.Segment> segments(HttpClient client, String baseUrl, String key, String model,
            @Nullable String language, List<String> terms, boolean diarize, byte[] audio, String filename,
            String mediaType, Duration timeout) throws IOException, InterruptedException {
        return run(client, baseUrl, key, model, language, terms, diarize, audio, filename, mediaType, timeout,
                SonioxAsync::group);
    }

    private static <T> T run(HttpClient client, String baseUrl, String key, String model, @Nullable String language,
            List<String> terms, boolean diarize, byte[] audio, String filename, String mediaType, Duration timeout,
            Function<JsonNode, T> read) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String file = null;
        String transcription = null;
        try {
            file = upload(client, baseUrl, key, audio, filename, mediaType, timeout).path("id").asString("");
            if (file.isEmpty()) throw ChatException.providerUnavailable();
            var body = new LinkedHashMap<String, Object>();
            body.put("model", asyncModel(model));
            body.put("file_id", file);
            if (diarize) body.put("enable_speaker_diarization", true);
            if (language != null) {
                body.put("language_hints", List.of(language));
                body.put("language_hints_strict", true);
            }
            if (!terms.isEmpty()) body.put("context", java.util.Map.of("terms", terms));
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
            return read.apply(send(client,
                    get(baseUrl + "/transcriptions/" + encode(transcription) + "/transcript", key, timeout)));
        } finally {
            if (transcription != null && !transcription.isEmpty())
                delete(client, baseUrl + "/transcriptions/" + encode(transcription), key, timeout);
            if (file != null && !file.isEmpty()) delete(client, baseUrl + "/files/" + encode(file), key, timeout);
        }
    }

    /**
     * Groups the transcript's tokens the way the live adapter groups them: one segment per speaker, broken where the
     * speaker changes, where the recording pauses, or where a sentence has run long enough to read on its own.
     */
    static List<LiveTranscription.Segment> group(JsonNode transcript) {
        var segments = new ArrayList<LiveTranscription.Segment>();
        String speaker = null;
        var text = new SpokenText();
        long startMs = 0;
        long endMs = 0;
        for (JsonNode token : transcript.path("tokens")) {
            String word = token.path("text").asString("");
            if (word.isEmpty() || "<fin>".equals(word) || "<end>".equals(word)) continue;
            // Soniox marks laughter, applause and the like as tokens of their own; they are not what was said.
            if (token.path("is_audio_event").asBoolean(false)) continue;
            String at = token.path("speaker").asString("1");
            if (at.isEmpty()) at = "1";
            long start = token.path("start_ms").asLong(0);
            long end = token.path("end_ms").asLong(start + token.path("duration_ms").asLong(0));
            boolean broken = speaker != null
                    && (!speaker.equals(at) || start - endMs > SEGMENT_GAP_MS || text.length() > MAX_SEGMENT_CHARS);
            if (broken) {
                add(segments, speaker, startMs, endMs, text);
                text.reset();
                speaker = null;
            }
            if (speaker == null) {
                speaker = at;
                startMs = start;
            }
            text.append(word, token.path("confidence").asDouble(1.0));
            endMs = Math.max(endMs, end);
        }
        if (speaker != null) add(segments, speaker, startMs, endMs, text);
        return List.copyOf(segments);
    }

    private static void add(List<LiveTranscription.Segment> segments, String speaker, long startMs, long endMs,
            SpokenText text) {
        if (text.isEmpty()) return;
        segments.add(new LiveTranscription.Segment(speaker, startMs, Math.max(endMs, startMs), text.said(),
                text.confidence(), text.spans()));
    }

    /** The connection holds the realtime model; its async sibling has the same version ({@code stt-rt-v5} → {@code stt-async-v5}). */
    static String asyncModel(String model) {
        if (model.startsWith("stt-async-")) return model;
        if (model.startsWith("stt-rt-")) return "stt-async-" + model.substring("stt-rt-".length());
        return DEFAULT_MODEL;
    }

    private static JsonNode upload(HttpClient client, String baseUrl, String key, byte[] audio, String filename,
            String mediaType, Duration timeout) throws IOException, InterruptedException {
        String boundary = "memoryos-" + UUID.randomUUID();
        // The parts are sent one after another, so a 500 MB recording is never copied into a second buffer.
        var body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(("--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + safe(filename)
                        + "\"\r\nContent-Type: " + mediaType + "\r\n\r\n").getBytes(UTF_8)),
                HttpRequest.BodyPublishers.ofByteArray(audio),
                HttpRequest.BodyPublishers.ofByteArray(("\r\n--" + boundary + "--\r\n").getBytes(UTF_8)));
        return send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/files")).timeout(timeout)
                .header("Authorization", "Bearer " + key).header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body).build());
    }

    /** Soniox detects the container itself, but the name must not break the multipart header. */
    private static String safe(String filename) {
        String name = filename.replaceAll("[\"\\r\\n\\\\]", "").strip();
        return name.isEmpty() ? "audio" : name;
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
