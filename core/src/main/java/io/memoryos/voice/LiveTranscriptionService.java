package io.memoryos.voice;

import io.memoryos.shared.ActorId;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Long-running speech-to-text streams over the Tenant's default speech-to-text connection. Soniox streams live with
 * speaker diarization; every other provider is cut into utterances at pauses and transcribed through its REST
 * adapter. Callers authorize the recording itself; this service bounds concurrency and records audio time as AI usage.
 */
@Service
public class LiveTranscriptionService {
    private static final Logger LOG = LoggerFactory.getLogger(LiveTranscriptionService.class);
    static final int MAX_STREAMS = 32;
    /** An online meeting streams the microphone and the shared tab at once. */
    static final int MAX_STREAMS_PER_ACTOR = 2;
    private final VoiceConnectionService connections;
    private final VoiceTranscriptionService transcription;
    private final MeterRegistry meters;
    private final @Nullable AiUsageRecorder usage;
    private final Semaphore streams = new Semaphore(MAX_STREAMS);
    private final Map<ActorId, Integer> perActor = new ConcurrentHashMap<>();

    public LiveTranscriptionService(VoiceConnectionService connections, VoiceTranscriptionService transcription,
                                    MeterRegistry meters, ObjectProvider<AiUsageRecorder> usage) {
        this.connections = connections;
        this.transcription = transcription;
        this.meters = meters;
        this.usage = usage.getIfAvailable();
    }

    /** What the opened stream runs on; {@code diarizes} tells whether speaker labels distinguish people. */
    public record Opened(LiveTranscription stream, String provider, String model, boolean diarizes) {}

    /**
     * Opens a stream for an already-authorized recording. {@code offsetMs} is the recording time the first appended
     * sample belongs to, so a stream reopened after a browser reconnect continues the same clock.
     */
    public Opened open(ActorId actor, LiveTranscription.Options options, long offsetMs, LiveTranscription.Listener listener) {
        if (offsetMs < 0) throw VoiceException.invalid("The recording offset cannot be negative.");
        var connection = connections.resolve(actor).stt();
        if (connection == null) throw VoiceException.providerUnavailable();
        String key = connections.key(connection);
        if (!acquire(actor)) throw VoiceException.busy();
        var released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                streams.release();
                perActor.computeIfPresent(actor, (ignored, count) -> count <= 1 ? null : count - 1);
            }
        };
        try {
            boolean soniox = connection.provider() == VoiceProvider.SONIOX;
            LiveTranscription stream = soniox
                    ? SonioxLiveTranscription.open(connection.provider().baseUrl(connection.endpoint()), key,
                            connection.sttModel(), options, offsetMs, listener)
                    : new ChunkedLiveTranscription(wav -> transcription.transcribe(connection, key, options.language(), wav),
                            offsetMs, listener);
            return new Opened(metered(stream, connection, actor, release), connection.provider().name(),
                    connection.sttModel(), soniox && options.diarize());
        } catch (RuntimeException unavailable) {
            release.run();
            meters.counter("memoryos.chat.voice.live.unavailable", "provider", connection.provider().name()).increment();
            throw VoiceException.providerUnavailable();
        }
    }

    private boolean acquire(ActorId actor) {
        var admitted = new AtomicBoolean();
        perActor.compute(actor, (ignored, count) -> {
            int current = count == null ? 0 : count;
            if (current >= MAX_STREAMS_PER_ACTOR || !streams.tryAcquire()) return count;
            admitted.set(true);
            return current + 1;
        });
        return admitted.get();
    }

    /** Counts appended audio and adds it to the AI usage ledger once, when the stream closes. */
    private LiveTranscription metered(LiveTranscription stream, VoiceConnectionService.Connection connection, ActorId actor,
                                      Runnable release) {
        var bytes = new AtomicLong();
        var closed = new AtomicBoolean();
        return new LiveTranscription() {
            @Override public void append(byte[] pcm) { stream.append(pcm); bytes.addAndGet(pcm.length); }
            @Override public CompletableFuture<Void> finish() { return stream.finish(); }
            @Override public void close() {
                if (!closed.compareAndSet(false, true)) return;
                try { stream.close(); }
                finally {
                    release.run();
                    record(connection, actor, bytes.get());
                }
            }
        };
    }

    private void record(VoiceConnectionService.Connection connection, ActorId actor, long bytes) {
        if (usage == null || bytes == 0) return;
        try {
            usage.record(new AiUsage(connection.tenantId(), actor.value(), AiUsageFlow.SPEECH_TO_TEXT,
                    connection.provider().name(), connection.sttModel(), connection.id(), null, null, 1, 0, 0, 0, 0,
                    bytes / (double) Pcm16.BYTES_PER_SECOND, null, Instant.now()));
        } catch (RuntimeException failure) {
            LOG.warn("Live transcription usage not recorded ({})", failure.getClass().getSimpleName());
        }
    }
}
