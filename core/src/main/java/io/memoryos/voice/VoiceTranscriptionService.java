package io.memoryos.voice;

import io.memoryos.BusinessException;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Speech-to-text sessions for voice input. Provider requests run on each session's worker, outside transactions. */
@Service
public class VoiceTranscriptionService {
    private static final Logger LOG = LoggerFactory.getLogger(VoiceTranscriptionService.class);
    /** Onyx limit per connection: about fourteen minutes of 24 kHz PCM16 audio. */
    public static final int MAX_RECORDING_BYTES = 25 * 1024 * 1024;
    private static final int MAX_SESSIONS = 16;
    private static final Set<String> LANGUAGES = Set.of("vi", "en");
    private final VoiceConnectionService connections;
    private final IamAuthorization authorization;
    private final MeterRegistry meters;
    private final VoiceAdapterRegistry adapters;
    private final ObservationRegistry observations;
    private final Semaphore sessions = new Semaphore(MAX_SESSIONS);
    private final Set<ActorId> active = ConcurrentHashMap.newKeySet();

    private @Nullable AiUsageRecorder usage;

    @Autowired
    public VoiceTranscriptionService(VoiceConnectionService connections, IamAuthorization authorization, MeterRegistry meters,
                                     VoiceAdapterRegistry adapters, ObservationRegistry observations,
                                     ObjectProvider<AiUsageRecorder> usage) {
        this(connections, authorization, meters, adapters, observations);
        this.usage = usage.getIfAvailable();
    }

    public VoiceTranscriptionService(VoiceConnectionService connections, IamAuthorization authorization, MeterRegistry meters,
                                     VoiceAdapterRegistry adapters, ObservationRegistry observations) {
        this.connections = connections;
        this.authorization = authorization;
        this.meters = meters;
        this.adapters = adapters;
        this.observations = observations;
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
        var realtime = adapters.realtime(connection.provider());
        if (realtime.isPresent()) {
            try {
                var session = realtime.get().openRealtime(connection, key, language, actor.value().toString(), batch, listener,
                        release, meters);
                if (session.isPresent()) return metered(session.get(), connection, actor);
            } catch (RuntimeException unavailable) {
                meters.counter("memoryos.chat.voice.realtime.fallback", "provider", connection.provider().name()).increment();
            }
        }
        return metered(new ChunkedTranscriber(batch, listener, release), connection, actor);
    }

    /** Counts the recorded audio and adds it to the AI usage ledger once, when the session closes. */
    private TranscriptionSession metered(TranscriptionSession session, VoiceConnectionService.Connection connection, ActorId actor) {
        if (usage == null) return session;
        var recorder = usage;
        var bytes = new AtomicLong();
        var recorded = new AtomicBoolean();
        return new TranscriptionSession() {
            @Override public void append(byte[] pcm) { session.append(pcm); bytes.addAndGet(pcm.length); }
            @Override public CompletableFuture<String> finish() { return session.finish(); }
            @Override public void close() {
                try { session.close(); }
                finally {
                    // PCM16 mono at 24 kHz is 48,000 bytes per second.
                    if (bytes.get() > 0 && recorded.compareAndSet(false, true)) {
                        try {
                            recorder.record(new AiUsage(connection.tenantId(), actor.value(),
                                    AiUsageFlow.SPEECH_TO_TEXT, connection.provider().name(), connection.sttModel(),
                                    connection.id(), null, null, 1, 0, 0, 0, 0, bytes.get() / 48_000.0, null, Instant.now()));
                        } catch (RuntimeException failure) {
                            LOG.atWarn().addKeyValue("event", "voice.transcription.usage_not_recorded")
                                    .addKeyValue("error_type", failure.getClass().getName()).log("Voice usage not recorded");
                        }
                    }
                }
            }
        };
    }

    /** Transcribes one 24 kHz WAV upload with the connection's provider, as one provider-call observation. */
    String transcribe(VoiceConnectionService.Connection connection, String key, @Nullable String language, byte[] wav) {
        var observation = VoiceObservations.start(observations, connection.provider(), "transcribe");
        String outcome = "failed";
        try (var _ = observation.openScope()) {
            String text = adapters.adapter(connection.provider()).transcribe(connection, key, language, wav);
            outcome = "succeeded";
            return text;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            VoiceObservations.error(observation, interrupted);
            throw VoiceException.providerUnavailable();
        } catch (RuntimeException failed) {
            // Provider payloads may carry account detail; report unavailability instead.
            VoiceObservations.error(observation, failed);
            throw VoiceException.providerUnavailable();
        } finally {
            VoiceObservations.stop(observation, outcome);
        }
    }

}
