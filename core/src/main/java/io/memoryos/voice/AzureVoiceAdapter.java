package io.memoryos.voice;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Azure AI Speech. Dictation and read-aloud stream through the Speech SDK (MEM-137); connection checks and clip
 * transcription, which the realtime fallback uses, stay on REST. The endpoint is the Speech resource endpoint;
 * recognition has no model choice and speech uses neural voices, labelled {@code default} and {@code neural} as in
 * Onyx. An uploaded recording is not transcribed.
 */
@Component
final class AzureVoiceAdapter implements RealtimeTranscriptionAdapter, SpeechSynthesisAdapter {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** Recognized when the member's language is unknown, as in Onyx's multi-locale Azure streaming. */
    static final List<String> AUTO_DETECT_LOCALES = List.of("vi-VN", "en-US");
    private static final VoiceProviderCapabilities CAPABILITIES = new VoiceProviderCapabilities("", true, true,
            List.of("default"), List.of("neural"),
            List.of("vi-VN-HoaiMyNeural", "vi-VN-NamMinhNeural", "en-US-JennyNeural", "en-US-GuyNeural"));
    private final AzureSpeechGateway gateway;

    AzureVoiceAdapter(AzureSpeechGateway gateway) {
        this.gateway = gateway;
    }

    @Override public VoiceProvider provider() { return VoiceProvider.AZURE; }
    @Override public VoiceProviderCapabilities capabilities() { return CAPABILITIES; }

    @Override public void verify(VoiceConnectionService.Probe probe) {
        VoiceChecks.arrayListing(probe.baseUrl() + AzureSpeech.VOICES_PATH, "Ocp-Apim-Subscription-Key", probe.key());
    }

    @Override public String transcribe(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                       @Nullable String language, byte[] wav) throws IOException, InterruptedException {
        return AzureSpeech.transcribe(http, CAPABILITIES.baseUrl(connection.endpoint()), key, language, wav,
                Pcm16.WAV_HEADER_BYTES, wav.length - Pcm16.WAV_HEADER_BYTES, REQUEST_TIMEOUT);
    }

    /** The member's language is recognized as given; without one, Vietnamese and English are identified continuously. */
    @Override public Optional<TranscriptionSession> openRealtime(VoiceConnectionService.Connection connection, String key,
            @Nullable String language, String user, Function<byte[], String> batch, Consumer<Transcript> listener,
            Runnable release, MeterRegistry meters) {
        var locales = language == null ? AUTO_DETECT_LOCALES : List.of(AzureSpeech.locale(language));
        return Optional.of(AzureRealtimeTranscriber.open(gateway, target(connection), key, locales, batch, listener,
                release, meters));
    }

    /** One SDK speech per text segment, read as it is synthesized; closing the stream stops the speech in progress. */
    @Override public ProviderSpeech speech(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                           double speed, Duration timeout) {
        var target = target(connection);
        String voice = connection.ttsVoice();
        return new ProviderSpeech() {
            @Override public Stream<byte[]> chunks(List<String> segments) {
                var reader = new SpeechReader(segments, text -> gateway.speak(target, key, AzureSpeech.ssml(voice, speed, text)));
                return StreamSupport.stream(Spliterators.spliteratorUnknownSize(reader, Spliterator.ORDERED), false)
                        .onClose(reader::close);
            }
            @Override public void close() {
                // Each chunk stream stops its own speech when it is closed.
            }
        };
    }

    private static AzureSpeechTarget target(VoiceConnectionService.Connection connection) {
        return AzureSpeechTarget.of(CAPABILITIES.baseUrl(connection.endpoint()));
    }

    /** MP3 chunks of consecutive segments; a segment's speech starts only when the previous one is read. */
    private static final class SpeechReader implements Iterator<byte[]> {
        private final Iterator<String> segments;
        private final Function<String, AzureSpeechGateway.Speech> speak;
        private AzureSpeechGateway.@Nullable Speech current;
        private byte @Nullable [] next;

        private SpeechReader(List<String> segments, Function<String, AzureSpeechGateway.Speech> speak) {
            this.segments = segments.iterator();
            this.speak = speak;
        }

        @Override
        public boolean hasNext() {
            while (next == null) {
                if (current == null) {
                    if (!segments.hasNext()) return false;
                    current = speak.apply(segments.next());
                }
                next = current.read();
                if (next == null) {
                    current.close();
                    current = null;
                }
            }
            return true;
        }

        @Override
        public byte[] next() {
            if (!hasNext()) throw new NoSuchElementException();
            byte[] chunk = next;
            next = null;
            return chunk;
        }

        void close() {
            if (current != null) current.close();
            current = null;
        }
    }
}
