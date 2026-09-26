package io.memoryos.voice;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Text-to-speech for reading answers aloud. Audio is streamed to the caller as the provider produces it and never stored. */
@Service
public class VoiceSynthesisService {
    private static final Logger LOG = LoggerFactory.getLogger(VoiceSynthesisService.class);
    /** Longest text one request or streaming speech reads aloud; Onyx has no limit. */
    public static final int MAX_TEXT_LENGTH = 32_000;
    public static final double MIN_SPEED = 0.5;
    public static final double MAX_SPEED = 2.0;
    /** OpenAI speech accepts at most 4096 characters per request. */
    static final int MAX_SEGMENT_LENGTH = 4_096;
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    /** Shared by request streams and streaming speeches. */
    private static final int MAX_STREAMS = 8;
    /**
     * One client for every REST speech request, without redirects so a credential never follows one elsewhere. A
     * speech that is stopped cancels its own request; nothing closes the client.
     */
    private static final HttpClient HTTP =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT).build();
    private final VoiceConnectionService connections;
    private final IamAuthorization authorization;
    private final MeterRegistry meters;
    private final Semaphore streams = new Semaphore(MAX_STREAMS);

    private @Nullable AiUsageRecorder usage;

    @Autowired
    public VoiceSynthesisService(VoiceConnectionService connections, IamAuthorization authorization, MeterRegistry meters,
                                 ObjectProvider<AiUsageRecorder> usage) {
        this(connections, authorization, meters);
        this.usage = usage.getIfAvailable();
    }

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
            throw VoiceException.invalid("Text to read aloud must contain 1 to 32000 characters.");
        var connection = acquire(actor, speed);
        Runnable release = releaseOnce();
        try {
            var opened = stream(connection, connections.key(connection), segments(input, MAX_SEGMENT_LENGTH), speed, release);
            record(connection, actor);
            return opened;
        } catch (RuntimeException failed) {
            // A key that cannot be decrypted or a provider that cannot be built must not keep the slot.
            release.run();
            throw failed;
        }
    }

    /** Starts reading an answer aloud while it is generated (Auto-Playback); parts are appended as they are ready. */
    public StreamingSynthesizer openStreaming(ActorId actor, double speed, Consumer<byte[]> audio) {
        requireAccess(actor);
        var connection = acquire(actor, speed);
        Runnable release = releaseOnce();
        try {
            var counted = new AtomicBoolean();
            return streaming(connection, connections.key(connection), speed, audio, release,
                    () -> { if (counted.compareAndSet(false, true)) record(connection, actor); });
        } catch (RuntimeException failed) {
            release.run();
            throw failed;
        }
    }

    /** Counts one read-aloud in the AI usage ledger; providers price by characters, which the ledger does not carry yet. */
    private void record(VoiceConnectionService.Connection connection, ActorId actor) {
        if (usage == null) return;
        try {
            usage.record(new AiUsage(connection.tenantId(), actor.value(), AiUsageFlow.TEXT_TO_SPEECH,
                    connection.provider().name(), connection.ttsModel(), connection.id(), null, null, 1, 0, 0, 0, 0, 0, null,
                    Instant.now()));
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "voice.synthesis.usage_not_recorded")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Voice usage not recorded");
        }
    }

    /** Returns the stream slot at most once, whichever of the failure path and the stream's own close runs first. */
    private Runnable releaseOnce() {
        var released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) streams.release();
        };
    }

    private VoiceConnectionService.Connection acquire(ActorId actor, double speed) {
        if (!(speed >= MIN_SPEED && speed <= MAX_SPEED)) throw VoiceException.invalid("Playback speed must be between 0.5 and 2.0.");
        var connection = connections.resolve(actor).tts();
        if (connection == null) throw VoiceException.providerUnavailable();
        if (!streams.tryAcquire()) throw VoiceException.busy();
        return connection;
    }

    SpeechStream stream(VoiceConnectionService.Connection connection, String key, List<String> segments, double speed,
            Runnable release) {
        var stream = new SpeechStream(connection, provider(connection, key, speed), segments, release);
        try {
            stream.start();
            return stream;
        } catch (RuntimeException failed) {
            stream.close();
            throw failed;
        }
    }

    /** {@code called} runs before each provider request, so usage is counted only once text reaches the provider. */
    private StreamingSynthesizer streaming(VoiceConnectionService.Connection connection, String key, double speed,
            Consumer<byte[]> audio, Runnable release, Runnable called) {
        var provider = provider(connection, key, speed);
        long started = System.nanoTime();
        return new StreamingSynthesizer(text -> { called.run(); return provider.chunks(List.of(text)); }, audio, outcome -> {
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

    /** MP3 for text segments from one provider at the member's speed; closing it releases the provider client. */
    private interface ProviderSpeech extends AutoCloseable {
        /** Lazy audio of consecutive provider requests; closing the stream cancels the request in progress. */
        Stream<byte[]> chunks(List<String> segments);

        @Override
        void close();
    }

    private static ProviderSpeech provider(VoiceConnectionService.Connection connection, String key, double speed) {
        String base = connection.provider().baseUrl(connection.endpoint());
        return switch (connection.provider()) {
            case OPENAI, OPENAI_COMPATIBLE -> new OpenAiSpeech(connection, key, speed);
            case ELEVENLABS -> new HttpSpeech(text -> ElevenLabsVoice.speech(base, key, connection.ttsModel(), connection.ttsVoice(),
                    speed, text, PROVIDER_TIMEOUT));
            case AZURE -> new HttpSpeech(text -> AzureSpeech.speech(base, key, connection.ttsVoice(), speed, text, PROVIDER_TIMEOUT));
            // Speech-to-text only; configuration never makes it a read-aloud connection.
            case SONIOX -> throw VoiceException.providerUnavailable();
        };
    }

    /**
     * REST providers on the shared client. Stopping a speech closes its chunk stream, which cancels the request in
     * flight, so there is no client of its own to shut down.
     */
    private static final class HttpSpeech implements ProviderSpeech {
        private final Function<String, HttpRequest> request;

        private HttpSpeech(Function<String, HttpRequest> request) {
            this.request = request;
        }

        @Override
        public Stream<byte[]> chunks(List<String> segments) {
            return HttpAudioStream.of(HTTP, segments, request);
        }

        @Override
        public void close() {
            // Each chunk stream cancels its own request when it is closed.
        }
    }

    /** Spring AI speech for OpenAI-protocol providers. */
    private static final class OpenAiSpeech implements ProviderSpeech {
        private final OpenAIClient client;
        private final OpenAiAudioSpeechModel model;

        private OpenAiSpeech(VoiceConnectionService.Connection connection, String key, double speed) {
            client = OpenAIOkHttpClient.builder().baseUrl(connection.provider().baseUrl(connection.endpoint()))
                    .apiKey(key.isEmpty() ? VoiceTranscriptionService.NO_CREDENTIAL : key)
                    .maxRetries(0).timeout(PROVIDER_TIMEOUT).build();
            var options = OpenAiAudioSpeechOptions.builder().model(connection.ttsModel()).voice(connection.ttsVoice())
                    .responseFormat(OpenAiAudioSpeechOptions.AudioResponseFormat.MP3).speed(speed).build();
            model = OpenAiAudioSpeechModel.builder().openAiClient(client).options(options).build();
        }

        @Override
        public Stream<byte[]> chunks(List<String> segments) {
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
                throw VoiceException.providerUnavailable();
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
                throw VoiceException.providerUnavailable();
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
