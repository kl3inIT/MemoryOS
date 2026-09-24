package io.memoryos.voice;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.models.audio.AudioResponseFormat;
import io.memoryos.BusinessException;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.shared.ActorId;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

/** Speech-to-text sessions for voice input. Provider requests run on each session's worker, outside transactions. */
@Service
public class VoiceTranscriptionService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(VoiceTranscriptionService.class);
    /** Onyx limit per connection: about fourteen minutes of 24 kHz PCM16 audio. */
    public static final int MAX_RECORDING_BYTES = 25 * 1024 * 1024;
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    /** One client for every REST call; the request carries its own timeout. */
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT).build();
    private static final int MAX_SESSIONS = 16;
    private static final Set<String> LANGUAGES = Set.of("vi", "en");
    /** OpenAI-protocol servers without authentication still receive a syntactically valid bearer value. */
    static final String NO_CREDENTIAL = "memoryos-no-credential";
    private final VoiceConnectionService connections;
    private final IamAuthorization authorization;
    private final MeterRegistry meters;
    private final Semaphore sessions = new Semaphore(MAX_SESSIONS);
    private final Set<ActorId> active = ConcurrentHashMap.newKeySet();

    private io.memoryos.usage.@org.jspecify.annotations.Nullable AiUsageRecorder usage;

    @org.springframework.beans.factory.annotation.Autowired
    public VoiceTranscriptionService(VoiceConnectionService connections, IamAuthorization authorization, MeterRegistry meters,
                                     org.springframework.beans.factory.ObjectProvider<io.memoryos.usage.AiUsageRecorder> usage) {
        this(connections, authorization, meters);
        this.usage = usage.getIfAvailable();
    }

    public VoiceTranscriptionService(VoiceConnectionService connections, IamAuthorization authorization, MeterRegistry meters) {
        this.connections = connections;
        this.authorization = authorization;
        this.meters = meters;
    }

    /** Voice input serves the Chat composer and Search, so either capability authorizes it. */
    public void requireAccess(ActorId actor) {
        try {
            authorization.require(actor, IamCapability.CHAT_WRITE, false);
        } catch (BusinessException chatDenied) {
            authorization.require(actor, IamCapability.SEARCH_READ, false);
        }
    }

    /** Opens one transcription session for an authorized member; each member has at most one at a time. */
    public TranscriptionSession open(ActorId actor, @Nullable String language, Consumer<Transcript> listener) {
        requireAccess(actor);
        if (language != null && !LANGUAGES.contains(language)) throw VoiceException.invalid("Unsupported voice language.");
        var connection = connections.resolve(actor).stt();
        if (connection == null) throw VoiceException.providerUnavailable();
        String key = connections.key(connection);
        if (!active.add(actor)) throw VoiceException.busy();
        if (!sessions.tryAcquire()) {
            active.remove(actor);
            throw VoiceException.busy();
        }
        Runnable release = () -> {
            sessions.release();
            active.remove(actor);
        };
        Function<byte[], String> batch = wav -> transcribe(connection, key, language, wav);
        if (connection.provider() == VoiceProvider.OPENAI && connection.endpoint().isEmpty()) {
            try {
                return metered(OpenAiRealtimeTranscriber.open(connection.provider().baseUrl(connection.endpoint()), key, language,
                        actor.value().toString(), batch, listener, release, meters), connection, actor);
            } catch (RuntimeException unavailable) {
                meters.counter("memoryos.chat.voice.realtime.fallback", "provider", VoiceProvider.OPENAI.name()).increment();
            }
        }
        if (connection.provider() == VoiceProvider.SONIOX) {
            try {
                return metered(SonioxRealtimeTranscriber.open(connection.provider().baseUrl(connection.endpoint()), key,
                        connection.sttModel(), language, batch, listener, release, meters), connection, actor);
            } catch (RuntimeException unavailable) {
                meters.counter("memoryos.chat.voice.realtime.fallback", "provider", VoiceProvider.SONIOX.name()).increment();
            }
        }
        return metered(new ChunkedTranscriber(batch, listener, release), connection, actor);
    }

    /** Counts the recorded audio and adds it to the AI usage ledger once, when the session closes. */
    private TranscriptionSession metered(TranscriptionSession session, VoiceConnectionService.Connection connection, ActorId actor) {
        if (usage == null) return session;
        var recorder = usage;
        var bytes = new java.util.concurrent.atomic.AtomicLong();
        var recorded = new java.util.concurrent.atomic.AtomicBoolean();
        return new TranscriptionSession() {
            @Override public void append(byte[] pcm) { session.append(pcm); bytes.addAndGet(pcm.length); }
            @Override public java.util.concurrent.CompletableFuture<String> finish() { return session.finish(); }
            @Override public void close() {
                try { session.close(); }
                finally {
                    // PCM16 mono at 24 kHz is 48,000 bytes per second.
                    if (bytes.get() > 0 && recorded.compareAndSet(false, true)) {
                        try {
                            recorder.record(new io.memoryos.usage.AiUsage(connection.tenantId(), actor.value(),
                                    io.memoryos.usage.AiUsageFlow.SPEECH_TO_TEXT, connection.provider().name(), connection.sttModel(),
                                    connection.id(), null, null, 1, 0, 0, 0, 0, bytes.get() / 48_000.0, null, java.time.Instant.now()));
                        } catch (RuntimeException failure) {
                            LOG.warn("Voice usage not recorded ({})", failure.getClass().getSimpleName());
                        }
                    }
                }
            }
        };
    }

    /** Transcribes one 24 kHz WAV upload with the connection's provider. */
    String transcribe(VoiceConnectionService.Connection connection, String key, @Nullable String language, byte[] wav) {
        long start = System.nanoTime();
        String outcome = "failed";
        String base = connection.provider().baseUrl(connection.endpoint());
        try {
            String text = switch (connection.provider()) {
                case OPENAI, OPENAI_COMPATIBLE -> openAi(connection, base, key, language, wav);
                case ELEVENLABS -> http(client -> ElevenLabsVoice.transcribe(client, base, key, connection.sttModel(), language,
                        wav, PROVIDER_TIMEOUT));
                case AZURE -> http(client -> AzureSpeech.transcribe(client, base, key, language, wav, Pcm16.WAV_HEADER_BYTES,
                        wav.length - Pcm16.WAV_HEADER_BYTES, PROVIDER_TIMEOUT));
                case SONIOX -> http(client -> SonioxAsync.transcribe(client, base, key, connection.sttModel(), language, wav,
                        PROVIDER_TIMEOUT));
            };
            outcome = "succeeded";
            return text;
        } catch (RuntimeException failed) {
            // Provider payloads may carry account detail; report unavailability instead.
            throw VoiceException.providerUnavailable();
        } finally {
            meters.timer("memoryos.chat.voice.request", "provider", connection.provider().name(), "operation", "transcribe",
                    "outcome", outcome).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private static String openAi(VoiceConnectionService.Connection connection, String base, String key, @Nullable String language,
            byte[] wav) {
        String credential = key.isEmpty() ? NO_CREDENTIAL : key;
        OpenAIClient client = OpenAIOkHttpClient.builder().baseUrl(base).apiKey(credential)
                .maxRetries(0).timeout(PROVIDER_TIMEOUT).build();
        OpenAIClientAsync async = OpenAIOkHttpClientAsync.builder().baseUrl(base).apiKey(credential)
                .maxRetries(0).timeout(PROVIDER_TIMEOUT).build();
        try {
            var options = OpenAiAudioTranscriptionOptions.builder().model(connection.sttModel())
                    .responseFormat(AudioResponseFormat.JSON);
            if (language != null) options.language(language);
            var model = OpenAiAudioTranscriptionModel.builder().openAiClient(client).openAiClientAsync(async)
                    .options(options.build()).build();
            return model.call(new AudioTranscriptionPrompt(new NamedAudio(wav))).getResult().getOutput();
        } finally {
            try { async.close(); } finally { client.close(); }
        }
    }

    @FunctionalInterface
    private interface HttpCall {
        String run(HttpClient client) throws IOException, InterruptedException;
    }

    /** REST providers use the JDK client without redirects, so a credential never follows a redirect elsewhere. */
    private static String http(HttpCall call) {
        try {
            return call.run(HTTP);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw VoiceException.providerUnavailable();
        } catch (IOException failed) {
            throw VoiceException.providerUnavailable();
        }
    }

    /** Spring AI derives the upload format from the resource file name. */
    private static final class NamedAudio extends ByteArrayResource {
        NamedAudio(byte[] wav) {
            super(wav);
        }

        @Override
        public String getFilename() {
            return "audio.wav";
        }
    }
}
