package io.memoryos.chat.voice;

import io.memoryos.chat.ChatException;
import io.memoryos.shared.ActorId;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Transcribes one whole recording that was uploaded rather than spoken live. The file is handed to the provider in
 * the container it arrived in: MemoryOS decodes no audio, because a browser cannot hold an hour of decoded speech and
 * the JVM reads neither MP3 nor AAC.
 *
 * <p>The answer is the same {@link LiveTranscription.Segment} a live stream produces, so a meeting stores an uploaded
 * recording exactly as it stores one it heard.
 */
@Service
public class BatchTranscriptionService {
    /** One recording at a time per process: a provider call holds the whole file in memory for as long as it runs. */
    private static final int MAX_CONCURRENT = 1;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** One client for every provider call; each request carries its own timeout. */
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT).build();
    /** Providers transcribe faster than real time; this is the ceiling for a five-hour recording. */
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(45);
    private static final int MAX_OPENAI_SEGMENTS = 20_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final VoiceConnectionService connections;
    private final @Nullable AiUsageRecorder usage;
    private final Semaphore running = new Semaphore(MAX_CONCURRENT);

    public BatchTranscriptionService(VoiceConnectionService connections, ObjectProvider<AiUsageRecorder> usage) {
        this.connections = connections;
        this.usage = usage.getIfAvailable();
    }

    /** One uploaded recording, in the container the person chose. */
    public record Recording(byte[] audio, String filename, String mediaType) {}

    /** What a recording became, and which connection read it, so the meeting can record both. */
    public record Transcribed(VoiceProvider provider, String model, boolean diarized,
                              List<LiveTranscription.Segment> segments) {}

    /**
     * A speech-to-text connection a member may transcribe a recording with, and what it will do with it: whether it
     * separates speakers and how large a file it takes. The limits are stated before a file is sent, not after.
     */
    public record Transcriber(UUID id, VoiceProvider provider, String model, boolean diarizes, long maxBytes,
                              boolean selected) {}

    /** The Tenant's connections that can transcribe an uploaded recording, the selected one first. */
    public List<Transcriber> transcribers(ActorId actor) {
        var all = connections.transcribers(actor);
        var listed = new ArrayList<Transcriber>(all.size());
        for (int i = 0; i < all.size(); i++) {
            var connection = all.get(i);
            if (!supports(connection.provider())) continue;
            listed.add(new Transcriber(connection.id(), connection.provider(), connection.sttModel(),
                    diarizes(connection.provider()), maxBytes(connection.provider()), i == 0));
        }
        return List.copyOf(listed);
    }

    /** Whether a provider can transcribe a whole uploaded file, as opposed to dictation only. */
    public static boolean supports(VoiceProvider provider) {
        return switch (provider) {
            case SONIOX, OPENAI, OPENAI_COMPATIBLE -> true;
            // Their current calls post WAV under a fixed name; an uploaded container needs its own work.
            case ELEVENLABS, AZURE -> false;
        };
    }

    /** Only Soniox separates speakers in a recording; elsewhere every sentence belongs to one speaker. */
    public static boolean diarizes(VoiceProvider provider) {
        return provider == VoiceProvider.SONIOX;
    }

    /** OpenAI refuses an audio upload above 25 MB; the others are bounded by what MemoryOS stores. */
    public static long maxBytes(VoiceProvider provider) {
        return provider == VoiceProvider.OPENAI ? 25L * 1024 * 1024 : 500L * 1024 * 1024;
    }

    /**
     * Transcribes the recording with the named provider, or the Tenant's selected one when none is named, and adds
     * the audio to the AI usage ledger.
     */
    public Transcribed transcribe(ActorId actor, @Nullable VoiceProvider provider,
            LiveTranscription.Options options, Recording recording) {
        var connection = connections.transcriber(actor, provider);
        if (!supports(connection.provider()))
            throw ChatException.invalid("This provider does not transcribe uploaded recordings.");
        if (recording.audio().length > maxBytes(connection.provider()))
            throw ChatException.invalid("The recording is larger than this provider accepts.");
        if (!running.tryAcquire()) throw ChatException.busy();
        try {
            String key = connections.key(connection);
            boolean diarize = options.diarize() && diarizes(connection.provider());
            var segments = switch (connection.provider()) {
                case SONIOX -> soniox(connection, key, options, diarize, recording);
                case OPENAI, OPENAI_COMPATIBLE -> openAi(connection, key, options, recording);
                case ELEVENLABS, AZURE -> throw ChatException.providerUnavailable();
            };
            record(connection, actor, segments);
            return new Transcribed(connection.provider(), connection.sttModel(), diarize, segments);
        } finally {
            running.release();
        }
    }

    private static List<LiveTranscription.Segment> soniox(VoiceConnectionService.Connection connection, String key,
            LiveTranscription.Options options, boolean diarize, Recording recording) {
        return call(client -> SonioxAsync.segments(client, connection.provider().baseUrl(connection.endpoint()), key,
                connection.sttModel(), options.language(), options.terms(), diarize, recording.audio(),
                recording.filename(), recording.mediaType(), MAX_TIMEOUT));
    }

    /**
     * OpenAI's verbose transcription answers timed segments for one speaker. The request is written by hand rather
     * than through the SDK because the SDK's transcription result carries only the text.
     */
    private static List<LiveTranscription.Segment> openAi(VoiceConnectionService.Connection connection, String key,
            LiveTranscription.Options options, Recording recording) {
        String base = connection.provider().baseUrl(connection.endpoint());
        return call(client -> {
            String boundary = "memoryos-" + UUID.randomUUID();
            var fields = new StringBuilder();
            field(fields, boundary, "model", connection.sttModel());
            field(fields, boundary, "response_format", "verbose_json");
            if (options.language() != null) field(fields, boundary, "language", options.language());
            fields.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"")
                    .append(recording.filename().replaceAll("[\"\\r\\n\\\\]", "")).append("\"\r\nContent-Type: ")
                    .append(recording.mediaType()).append("\r\n\r\n");
            // Sent as three parts, so the recording is never copied into a second buffer.
            var body = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString(fields.toString(), StandardCharsets.UTF_8),
                    HttpRequest.BodyPublishers.ofByteArray(recording.audio()),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n", StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(URI.create(base + "/audio/transcriptions")).timeout(MAX_TIMEOUT)
                    .header("Authorization", "Bearer " + (key.isEmpty() ? "not-required" : key))
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(body).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw ChatException.providerUnavailable();
            return segments(JSON.readTree(response.body()));
        });
    }

    static List<LiveTranscription.Segment> segments(JsonNode transcription) {
        var segments = new ArrayList<LiveTranscription.Segment>();
        for (JsonNode segment : transcription.path("segments")) {
            String text = segment.path("text").asString("").strip();
            if (text.isEmpty()) continue;
            long start = Math.round(segment.path("start").asDouble(0) * 1000);
            long end = Math.round(segment.path("end").asDouble(0) * 1000);
            // Onyx reads avg_logprob the same way: a log probability back to a share between 0 and 1.
            double confidence = Math.clamp(Math.exp(segment.path("avg_logprob").asDouble(0)), 0, 1);
            segments.add(new LiveTranscription.Segment("1", start, Math.max(end, start), text, confidence));
            if (segments.size() >= MAX_OPENAI_SEGMENTS) break;
        }
        if (segments.isEmpty()) {
            String text = transcription.path("text").asString("").strip();
            // A server without verbose segments still answers the whole text; it becomes one utterance.
            if (!text.isEmpty()) segments.add(new LiveTranscription.Segment("1", 0, 0, text, 1));
        }
        return List.copyOf(segments);
    }

    private static void field(StringBuilder body, String boundary, String name, String value) {
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name)
                .append("\"\r\n\r\n").append(value).append("\r\n");
    }

    /** The recorded length the provider reported, not the file's size: compressed bytes say nothing about seconds. */
    private void record(VoiceConnectionService.Connection connection, ActorId actor,
            List<LiveTranscription.Segment> segments) {
        if (usage == null || segments.isEmpty()) return;
        double seconds = segments.getLast().endMs() / 1000.0;
        if (seconds <= 0) return;
        try {
            usage.record(new AiUsage(connection.tenantId(), actor.value(), AiUsageFlow.SPEECH_TO_TEXT,
                    connection.provider().name(), connection.sttModel(), connection.id(), null, null, 1, 0, 0, 0, 0,
                    seconds, null, Instant.now()));
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(BatchTranscriptionService.class)
                    .warn("Recording usage not recorded ({})", failure.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface ProviderCall {
        List<LiveTranscription.Segment> run(HttpClient client) throws IOException, InterruptedException;
    }

    /** As the dictation path: no redirects, so a credential never follows one elsewhere. */
    private static List<LiveTranscription.Segment> call(ProviderCall call) {
        try {
            return call.run(HTTP);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ChatException.providerUnavailable();
        } catch (IOException | RuntimeException failed) {
            if (failed instanceof ChatException known) throw known;
            throw ChatException.providerUnavailable();
        }
    }
}
