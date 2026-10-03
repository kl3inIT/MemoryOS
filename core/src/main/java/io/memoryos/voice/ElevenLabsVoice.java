package io.memoryos.voice;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ElevenLabs REST adapter (Onyx ElevenLabs parity without the realtime sockets): Scribe speech-to-text and streamed
 * text-to-speech with the {@code xi-api-key} header.
 */
final class ElevenLabsVoice {
    /** ElevenLabs accepts speech speed from 0.7 to 1.2. */
    static final double MIN_SPEED = 0.7;
    static final double MAX_SPEED = 1.2;
    static final String OUTPUT_FORMAT = "mp3_44100_128";
    /** The text of a dictation clip, which is at most a few minutes of speech. */
    private static final int MAX_TRANSCRIPT_BYTES = 1_048_576;
    private static final MediaType WAV = MediaType.parseMediaType("audio/wav");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ElevenLabsVoice() {}

    /** Transcribes a WAV upload; the language is an ISO-639-1 code such as {@code vi}. */
    static String transcribe(String baseUrl, String key, String model, @Nullable String language, byte[] wav,
            Duration timeout) {
        var form = new MultipartBodyBuilder();
        form.part("model_id", model);
        if (language != null) form.part("language_code", language);
        form.part("file", wav, WAV).filename("audio.wav");
        try {
            JsonNode transcript = OutboundHttp.builder(new Limits(timeout, MAX_TRANSCRIPT_BYTES)).build().post()
                    .uri(URI.create(baseUrl + "/speech-to-text")).header("xi-api-key", key)
                    .accept(MediaType.APPLICATION_JSON).contentType(MediaType.MULTIPART_FORM_DATA).body(form.build())
                    .retrieve().body(JsonNode.class);
            return transcript == null ? "" : transcript.path("text").asString("").strip();
        } catch (RestClientException failed) {
            // A failed answer carries its status only, so account detail cannot reach a response or a log.
            throw VoiceException.providerUnavailable();
        }
    }

    /** One streamed MP3 request for a text segment, at the member's speed within the range ElevenLabs accepts. */
    static HttpRequest speech(String baseUrl, String key, String model, String voice, double speed, String text,
            Duration timeout) {
        var body = Map.of("text", text, "model_id", model,
                "voice_settings", Map.of("speed", Math.clamp(speed, MIN_SPEED, MAX_SPEED)));
        String path = "/text-to-speech/" + URLEncoder.encode(voice, UTF_8).replace("+", "%20") + "/stream?output_format="
                + OUTPUT_FORMAT;
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("xi-api-key", key).header("Content-Type", "application/json").header("Accept", "audio/mpeg")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }
}
