package io.memoryos.chat.voice;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.memoryos.chat.ChatException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Azure AI Speech REST adapter (decision Q1: REST only). The endpoint is the Speech resource endpoint. Speech-to-text
 * uses the short-audio API, which accepts 16 kHz WAV and at most 60 seconds per request, so longer recordings are sent
 * in consecutive parts. Text-to-speech sends escaped SSML and streams MP3.
 */
final class AzureSpeech {
    static final String STT_PATH = "/stt/speech/recognition/conversation/cognitiveservices/v1";
    static final String TTS_PATH = "/tts/cognitiveservices/v1";
    static final String VOICES_PATH = "/tts/cognitiveservices/voices/list";
    static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";
    /** Below the 60-second short-audio limit. */
    static final int PART_SECONDS = 55;
    private static final int PART_BYTES = PART_SECONDS * Pcm16.BYTES_PER_SECOND_16K;
    private static final Set<String> SILENT = Set.of("NoMatch", "InitialSilenceTimeout", "BabbleTimeout");
    private static final Pattern VOICE_LOCALE = Pattern.compile("^([a-z]{2,3}-[A-Z]{2})-");
    private static final ObjectMapper JSON = new ObjectMapper();

    private AzureSpeech() {}

    /** The recognition locale for the member's display language. */
    static String locale(@Nullable String language) {
        return "en".equals(language) ? "en-US" : "vi-VN";
    }

    /** Transcribes 24 kHz PCM16 audio; silence and unmatched speech yield empty text rather than an error. */
    static String transcribe(HttpClient client, String endpoint, String key, @Nullable String language, byte[] pcm,
            int offset, int length, Duration timeout) throws IOException, InterruptedException {
        byte[] audio = Pcm16.resampleTo16k(pcm, offset, length);
        var parts = new ArrayList<String>();
        for (int start = 0; start < audio.length; start += PART_BYTES) {
            int size = Math.min(PART_BYTES, audio.length - start);
            var request = HttpRequest.newBuilder(URI.create(endpoint + STT_PATH + "?language=" + locale(language) + "&format=simple"))
                    .timeout(timeout).header("Ocp-Apim-Subscription-Key", key).header("Accept", "application/json")
                    .header("Content-Type", "audio/wav; codecs=audio/pcm; samplerate=16000")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Pcm16.wav(audio, start, size, Pcm16.SAMPLE_RATE_16K))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw ChatException.providerUnavailable();
            var result = JSON.readTree(response.body());
            String status = result.path("RecognitionStatus").asString("");
            if ("Success".equals(status)) {
                String text = result.path("DisplayText").asString("").strip();
                if (!text.isEmpty()) parts.add(text);
            } else if (!SILENT.contains(status)) {
                throw ChatException.providerUnavailable();
            }
        }
        return String.join(" ", parts);
    }

    /** One streamed MP3 request for a text segment; the voice's locale names the SSML language. */
    static HttpRequest speech(String endpoint, String key, String voice, double speed, String text, Duration timeout) {
        var matcher = VOICE_LOCALE.matcher(voice);
        String language = matcher.find() ? matcher.group(1) : "en-US";
        String ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"" + escape(language) + "\">"
                + "<voice name=\"" + escape(voice) + "\"><prosody rate=\"" + String.format(Locale.ROOT, "%.2f", speed) + "\">"
                + escape(text) + "</prosody></voice></speak>";
        return HttpRequest.newBuilder(URI.create(endpoint + TTS_PATH)).timeout(timeout)
                .header("Ocp-Apim-Subscription-Key", key).header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", OUTPUT_FORMAT).header("User-Agent", "MemoryOS")
                .POST(HttpRequest.BodyPublishers.ofString(ssml, UTF_8)).build();
    }

    /** XML text and attribute escaping, so answer text cannot change the SSML document. */
    static String escape(String value) {
        var escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> {
                    // XML 1.0 forbids most control characters.
                    if (character >= 0x20 || character == '\t' || character == '\n' || character == '\r') escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }
}
