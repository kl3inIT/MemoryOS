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
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private ElevenLabsVoice() {}

    /** Transcribes a WAV upload; the language is an ISO-639-1 code such as {@code vi}. */
    static String transcribe(HttpClient client, String baseUrl, String key, String model, @Nullable String language,
            byte[] wav, Duration timeout) throws IOException, InterruptedException {
        String boundary = "memoryos-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream(wav.length + 512);
        field(body, boundary, "model_id", model);
        if (language != null) field(body, boundary, "language_code", language);
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(UTF_8));
        body.writeBytes(wav);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(UTF_8));
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/speech-to-text")).timeout(timeout)
                .header("xi-api-key", key).header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw ChatException.providerUnavailable();
        return JSON.readTree(response.body()).path("text").asString("").strip();
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

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value
                + "\r\n").getBytes(UTF_8));
    }
}
