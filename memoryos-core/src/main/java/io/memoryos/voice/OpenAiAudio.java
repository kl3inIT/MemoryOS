package io.memoryos.voice;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.models.audio.AudioResponseFormat;
import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClientException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

/** The OpenAI audio protocol, shared by OpenAI and by self-hosted or gateway servers that speak it. */
final class OpenAiAudio {
    /** OpenAI-protocol servers without authentication still receive a syntactically valid bearer value. */
    static final String NO_CREDENTIAL = "memoryos-no-credential";
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_SEGMENTS = 20_000;
    /** A verbose transcription of a long recording lists every segment with its token ids. */
    private static final int MAX_TRANSCRIPTION_BYTES = 64 * 1024 * 1024;

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
     * Verbose transcription answers timed segments for one speaker. It is not sent through the OpenAI SDK: Spring AI's
     * transcription model returns the text only, and the SDK's own typed answer refuses what compatible servers send
     * (a segment without {@code avg_logprob}, or segments without a duration). The answer is read as a tree instead.
     */
    static List<LiveTranscription.Segment> segments(String base, String model, String key,
            LiveTranscription.Options options, BatchTranscriptionService.Recording recording, Duration timeout) {
        var form = new MultipartBodyBuilder();
        form.part("model", model);
        form.part("response_format", "verbose_json");
        if (options.language() != null) form.part("language", options.language());
        // The recording is read from its source as the part is written.
        form.part("file", AudioSource.part(recording.audio(), recording.sizeBytes(), recording.filename()),
                MediaType.parseMediaType(recording.mediaType()));
        try {
            JsonNode transcription = OutboundHttp.builder(new Limits(timeout, MAX_TRANSCRIPTION_BYTES)).build().post()
                    .uri(URI.create(base + "/audio/transcriptions"))
                    .headers(sent -> sent.setBearerAuth(key.isEmpty() ? "not-required" : key))
                    .accept(MediaType.APPLICATION_JSON).contentType(MediaType.MULTIPART_FORM_DATA).body(form.build())
                    .retrieve().body(JsonNode.class);
            if (transcription == null) throw VoiceException.providerUnavailable();
            return segments(transcription);
        } catch (RestClientException failed) {
            // A failed answer carries its status only, so account detail cannot reach a response or a log.
            throw VoiceException.providerUnavailable();
        }
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

    /** Spring AI derives the upload format from the resource file name. */
    private static final class NamedAudio extends ByteArrayResource {
        NamedAudio(byte[] wav) { super(wav); }
        @Override public String getFilename() { return "audio.wav"; }
    }
}
