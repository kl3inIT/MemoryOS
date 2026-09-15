package io.memoryos.chat.voice;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.memoryos.chat.ChatException;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Text-to-speech for reading answers aloud. Audio is streamed to the caller as the provider produces it and never stored. */
@Service
public class VoiceSynthesisService {
    /** Longest text one request or streaming speech reads aloud; Onyx has no limit. */
    public static final int MAX_TEXT_LENGTH = 32_000;
    public static final double MIN_SPEED = 0.5;
    public static final double MAX_SPEED = 2.0;
    /** OpenAI speech accepts at most 4096 characters per request. */
    static final int MAX_SEGMENT_LENGTH = 4_096;
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(60);
    /** Shared by request streams and streaming speeches. */
    private static final int MAX_STREAMS = 8;
    private final VoiceConnectionService connections;
    private final IamAuthorization authorization;
    private final MeterRegistry meters;
    private final Semaphore streams = new Semaphore(MAX_STREAMS);

    public VoiceSynthesisService(VoiceConnectionService connections, IamAuthorization authorization, MeterRegistry meters) {
        this.connections = connections;
        this.authorization = authorization;
        this.meters = meters;
    }

    /** Reading answers aloud serves Chat readers. */
    public void requireAccess(ActorId actor) {
        authorization.require(actor, IamCapability.CHAT_READ, false);
    }

    /**
     * Starts reading text aloud for a Chat reader with the Tenant's default text-to-speech provider. The first audio chunk
     * is fetched before this returns, so a rejected key or unreachable provider is still reported as an error response.
     */
    public SpeechStream open(ActorId actor, String text, double speed) {
        requireAccess(actor);
        String input = text.strip();
        if (input.isEmpty() || text.length() > MAX_TEXT_LENGTH)
            throw ChatException.invalid("Text to read aloud must contain 1 to 32000 characters.");
        var connection = acquire(actor, speed);
        String key = connections.key(connection);
        return stream(connection, key, segments(input, MAX_SEGMENT_LENGTH), speed, streams::release);
    }

    /** Starts reading an answer aloud while it is generated (Auto-Playback); parts are appended as they are ready. */
    public StreamingSynthesizer openStreaming(ActorId actor, double speed, Consumer<byte[]> audio) {
        requireAccess(actor);
        var connection = acquire(actor, speed);
        return streaming(connection, connections.key(connection), speed, audio, streams::release);
    }

    private VoiceConnectionService.Connection acquire(ActorId actor, double speed) {
        if (!(speed >= MIN_SPEED && speed <= MAX_SPEED)) throw ChatException.invalid("Playback speed must be between 0.5 and 2.0.");
        var connection = connections.resolve(actor).tts();
        if (connection == null) throw ChatException.providerUnavailable();
        if (!streams.tryAcquire()) throw ChatException.busy();
        return connection;
    }

    SpeechStream stream(VoiceConnectionService.Connection connection, String key, List<String> segments, double speed,
            Runnable release) {
        var stream = new SpeechStream(connection, new ProviderSpeech(connection, key, speed), segments, release);
        try {
            stream.start();
            return stream;
        } catch (RuntimeException failed) {
            stream.close();
            throw failed;
        }
    }

    StreamingSynthesizer streaming(VoiceConnectionService.Connection connection, String key, double speed,
            Consumer<byte[]> audio, Runnable release) {
        var provider = new ProviderSpeech(connection, key, speed);
        long started = System.nanoTime();
        return new StreamingSynthesizer(text -> provider.chunks(List.of(text)), audio, outcome -> {
            try {
                provider.close();
            } finally {
                release.run();
                record(connection, outcome, started);
            }
        });
    }

    private void record(VoiceConnectionService.Connection connection, String outcome, long started) {
        meters.timer("memoryos.chat.voice.request", "provider", connection.provider().name(), "operation", "synthesize",
                "outcome", outcome).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    /** Splits text into provider-sized parts, preferring sentence ends, then whitespace; surrogate pairs stay whole. */
    static List<String> segments(String text, int max) {
        var result = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + max);
            if (end < text.length()) {
                end = boundary(text, start, end);
                if (Character.isLowSurrogate(text.charAt(end)) && end - 1 > start) end--;
            }
            String segment = text.substring(start, end).strip();
            if (!segment.isEmpty()) result.add(segment);
            start = end;
        }
        return result;
    }

    private static int boundary(String text, int start, int end) {
        int space = -1;
        for (int i = end - 1; i > start; i--) {
            char current = text.charAt(i);
            if (current == '\n' || (isSentenceEnd(current) && Character.isWhitespace(text.charAt(i + 1)))) return i + 1;
            if (space < 0 && Character.isWhitespace(current)) space = i + 1;
        }
        return space > 0 ? space : end;
    }

    private static boolean isSentenceEnd(char value) {
        return value == '.' || value == '!' || value == '?' || value == '…' || value == '。';
    }

    /** One provider client and model for a speech; MP3 in the member's speed. */
    private static final class ProviderSpeech implements AutoCloseable {
        private final OpenAIClient client;
        private final OpenAiAudioSpeechModel model;

        private ProviderSpeech(VoiceConnectionService.Connection connection, String key, double speed) {
            client = OpenAIOkHttpClient.builder().baseUrl(connection.provider().baseUrl(connection.endpoint()))
                    .apiKey(key.isEmpty() ? VoiceTranscriptionService.NO_CREDENTIAL : key)
                    .maxRetries(0).timeout(PROVIDER_TIMEOUT).build();
            var options = OpenAiAudioSpeechOptions.builder().model(connection.ttsModel()).voice(connection.ttsVoice())
                    .responseFormat(OpenAiAudioSpeechOptions.AudioResponseFormat.MP3).speed(speed).build();
            model = OpenAiAudioSpeechModel.builder().openAiClient(client).options(options).build();
        }

        /** Lazy audio of consecutive provider requests; closing the stream cancels the request in progress. */
        private Stream<byte[]> chunks(List<String> segments) {
            return Flux.fromIterable(segments)
                    .concatMap(segment -> model.stream(new TextToSpeechPrompt(segment)))
                    .map(response -> response.getResult().getOutput())
                    .filter(bytes -> bytes.length > 0)
                    .toStream(1);
        }

        @Override
        public void close() {
            client.close();
        }
    }

    /** One read-aloud response: MP3 chunks from consecutive provider requests, in text order. */
    public final class SpeechStream implements AutoCloseable {
        private final VoiceConnectionService.Connection connection;
        private final ProviderSpeech provider;
        private final Stream<byte[]> chunks;
        private final Iterator<byte[]> iterator;
        private final Runnable release;
        private final long started = System.nanoTime();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile String outcome = "cancelled";
        private byte @Nullable [] first;

        private SpeechStream(VoiceConnectionService.Connection connection, ProviderSpeech provider, List<String> segments,
                Runnable release) {
            this.connection = connection;
            this.provider = provider;
            this.release = release;
            chunks = provider.chunks(segments);
            iterator = chunks.iterator();
        }

        private void start() {
            first = next();
            if (first == null) {
                outcome = "failed";
                throw ChatException.providerUnavailable();
            }
        }

        /** Writes the audio; a provider failure after the first chunk ends the response early. */
        public void writeTo(OutputStream output) throws IOException {
            try {
                for (byte[] chunk = first; chunk != null; chunk = next()) {
                    first = null;
                    output.write(chunk);
                    output.flush();
                }
                outcome = "succeeded";
            } finally {
                close();
            }
        }

        private byte @Nullable [] next() {
            try {
                return iterator.hasNext() ? iterator.next() : null;
            } catch (RuntimeException failed) {
                // Provider payloads may carry account detail; report unavailability instead.
                outcome = "failed";
                throw ChatException.providerUnavailable();
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                chunks.close();
            } finally {
                try {
                    provider.close();
                } finally {
                    release.run();
                    record(connection, outcome, started);
                }
            }
        }
    }
}
