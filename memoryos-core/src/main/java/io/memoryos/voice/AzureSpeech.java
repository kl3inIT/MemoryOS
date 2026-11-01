package io.memoryos.voice;

import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Azure AI Speech over REST: connection checks, clip transcription and the realtime fallback. Realtime dictation and
 * read-aloud use the Speech SDK (MEM-137). The endpoint is the Speech resource endpoint. Speech-to-text
 * uses the short-audio API, which accepts 16 kHz WAV and at most 60 seconds per request, so longer recordings are sent
 * in consecutive parts.
 */
final class AzureSpeech {
    static final String STT_PATH = "/stt/speech/recognition/conversation/cognitiveservices/v1";
    static final String VOICES_PATH = "/tts/cognitiveservices/voices/list";
    /** Below the 60-second short-audio limit. */
    static final int PART_SECONDS = 55;
    private static final int PART_BYTES = PART_SECONDS * Pcm16.BYTES_PER_SECOND_16K;
    private static final Set<String> SILENT = Set.of("NoMatch", "InitialSilenceTimeout", "BabbleTimeout");
    private static final Pattern VOICE_LOCALE = Pattern.compile("^([a-z]{2,3}-[A-Z]{2})-");
    /** As Azure documents it; the unquoted {@code codecs} value is not a media type Spring accepts. */
    private static final String CONTENT_TYPE = "audio/wav; codecs=audio/pcm; samplerate=16000";
    /** The text of at most 55 seconds of speech. */
    private static final int MAX_RESULT_BYTES = 1_048_576;

    private AzureSpeech() {}

    /** The recognition locale for the member's display language. */
    static String locale(@Nullable String language) {
        return "en".equals(language) ? "en-US" : "vi-VN";
    }

    /** Transcribes 24 kHz PCM16 audio; silence and unmatched speech yield empty text rather than an error. */
    static String transcribe(String endpoint, String key, @Nullable String language, byte[] pcm, int offset, int length,
            Duration timeout) {
        byte[] audio = Pcm16.resampleTo16k(pcm, offset, length);
        var client = OutboundHttp.builder(new Limits(timeout, MAX_RESULT_BYTES)).build();
        var parts = new ArrayList<String>();
        for (int start = 0; start < audio.length; start += PART_BYTES) {
            int size = Math.min(PART_BYTES, audio.length - start);
            JsonNode result;
            try {
                byte[] wav = Pcm16.wav(audio, start, size, Pcm16.SAMPLE_RATE_16K);
                // Written as bytes, not through a converter: Spring refuses to parse the content type Azure asks for.
                result = client.post().uri(URI.create(endpoint + STT_PATH + "?language=" + locale(language) + "&format=simple"))
                        .header("Ocp-Apim-Subscription-Key", key).accept(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE).contentLength(wav.length)
                        .body(body -> body.write(wav)).retrieve().body(JsonNode.class);
            } catch (RestClientException failed) {
                // A failed answer carries its status only, so account detail cannot reach a response or a log.
                throw VoiceException.providerUnavailable();
            }
            if (result == null) throw VoiceException.providerUnavailable();
            String status = result.path("RecognitionStatus").asString("");
            if ("Success".equals(status)) {
                String text = result.path("DisplayText").asString("").strip();
                if (!text.isEmpty()) parts.add(text);
            } else if (!SILENT.contains(status)) {
                throw VoiceException.providerUnavailable();
            }
        }
        return String.join(" ", parts);
    }

    /** The SSML for one text segment, for REST and the Speech SDK alike; the voice's locale names the language. */
    static String ssml(String voice, double speed, String text) {
        var matcher = VOICE_LOCALE.matcher(voice);
        String language = matcher.find() ? matcher.group(1) : "en-US";
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"" + escape(language) + "\">"
                + "<voice name=\"" + escape(voice) + "\"><prosody rate=\"" + String.format(Locale.ROOT, "%.2f", speed) + "\">"
                + escape(text) + "</prosody></voice></speak>";
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
