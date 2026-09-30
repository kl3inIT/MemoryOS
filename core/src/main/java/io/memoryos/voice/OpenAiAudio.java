package io.memoryos.voice;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.models.audio.AudioResponseFormat;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The OpenAI audio protocol, shared by OpenAI and by self-hosted or gateway servers that speak it. */
final class OpenAiAudio {
    /** OpenAI-protocol servers without authentication still receive a syntactically valid bearer value. */
    static final String NO_CREDENTIAL = "memoryos-no-credential";
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_SEGMENTS = 20_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiAudio() {}

    /** Onyx validate_credentials parity: an authorized model listing proves the endpoint and credential. */
    static void verify(VoiceConnectionService.Probe probe) {
        var headers = new HashMap<String, String>();
        if (!probe.key().isEmpty()) headers.put("Authorization", "Bearer " + probe.key());
        VoiceChecks.jsonListing(probe.baseUrl() + "/models", headers, "data");
    }

    static String transcribe(String base, String model, String key, @Nullable String language, byte[] wav) {
        String credential = key.isEmpty() ? NO_CREDENTIAL : key;
        OpenAIClient client = OpenAIOkHttpClient.builder().baseUrl(base).apiKey(credential)
                .maxRetries(0).timeout(PROVIDER_TIMEOUT).build();
        OpenAIClientAsync async = OpenAIOkHttpClientAsync.builder().baseUrl(base).apiKey(credential)
                .maxRetries(0).timeout(PROVIDER_TIMEOUT).build();
        try {
            var options = OpenAiAudioTranscriptionOptions.builder().model(model).responseFormat(AudioResponseFormat.JSON);
            if (language != null) options.language(language);
            var transcription = OpenAiAudioTranscriptionModel.builder().openAiClient(client).openAiClientAsync(async)
                    .options(options.build()).build();
            return transcription.call(new AudioTranscriptionPrompt(new NamedAudio(wav))).getResult().getOutput();
        } finally {
            try { async.close(); } finally { client.close(); }
        }
    }

    /**
     * Verbose transcription answers timed segments for one speaker. The request is written by hand rather than through
     * the SDK because the SDK's transcription result carries only the text.
     */
    static List<LiveTranscription.Segment> segments(HttpClient http, String base, String model, String key,
            LiveTranscription.Options options, BatchTranscriptionService.Recording recording, Duration timeout)
            throws IOException, InterruptedException {
        String boundary = "memoryos-" + UUID.randomUUID();
        var fields = new StringBuilder();
        field(fields, boundary, "model", model);
        field(fields, boundary, "response_format", "verbose_json");
        if (options.language() != null) field(fields, boundary, "language", options.language());
        fields.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"")
                .append(recording.filename().replaceAll("[\"\\r\\n\\\\]", "")).append("\"\r\nContent-Type: ")
                .append(recording.mediaType()).append("\r\n\r\n");
        // Sent as three parts, and the recording is read from its source as it is sent.
        var body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(fields.toString(), StandardCharsets.UTF_8),
                AudioSource.body(recording.audio(), recording.sizeBytes()),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n", StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(base + "/audio/transcriptions")).timeout(timeout)
                .header("Authorization", "Bearer " + (key.isEmpty() ? "not-required" : key))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw VoiceException.providerUnavailable();
        return segments(JSON.readTree(response.body()));
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
            if (segments.size() >= MAX_SEGMENTS) break;
        }
        if (segments.isEmpty()) {
            String text = transcription.path("text").asString("").strip();
            // A server without verbose segments still answers the whole text; it becomes one utterance.
            if (!text.isEmpty()) segments.add(new LiveTranscription.Segment("1", 0, 0, text, 1));
        }
        return List.copyOf(segments);
    }

    /** Spring AI speech; closing it closes the SDK client. */
    static ProviderSpeech speech(String base, String model, String voice, String key, double speed, Duration timeout) {
        OpenAIClient client = OpenAIOkHttpClient.builder().baseUrl(base).apiKey(key.isEmpty() ? NO_CREDENTIAL : key)
                .maxRetries(0).timeout(timeout).build();
        var options = OpenAiAudioSpeechOptions.builder().model(model).voice(voice)
                .responseFormat(OpenAiAudioSpeechOptions.AudioResponseFormat.MP3).speed(speed).build();
        var speech = OpenAiAudioSpeechModel.builder().openAiClient(client).options(options).build();
        return new ProviderSpeech() {
            @Override public Stream<byte[]> chunks(List<String> segments) {
                return Flux.fromIterable(segments)
                        .concatMap(segment -> speech.stream(new TextToSpeechPrompt(segment)))
                        .map(response -> response.getResult().getOutput())
                        .filter(bytes -> bytes.length > 0)
                        .toStream(1);
            }
            @Override public void close() { client.close(); }
        };
    }

    private static void field(StringBuilder body, String boundary, String name, String value) {
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name)
                .append("\"\r\n\r\n").append(value).append("\r\n");
    }

    /** Spring AI derives the upload format from the resource file name. */
    private static final class NamedAudio extends ByteArrayResource {
        NamedAudio(byte[] wav) { super(wav); }
        @Override public String getFilename() { return "audio.wav"; }
    }
}
