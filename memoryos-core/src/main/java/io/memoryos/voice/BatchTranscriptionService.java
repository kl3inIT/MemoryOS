package io.memoryos.voice;

import io.memoryos.shared.ActorId;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Transcribes one whole recording that was uploaded rather than spoken live. The file is handed to the provider in
 * the container it arrived in: MemoryOS decodes no audio, because a browser cannot hold an hour of decoded speech and
 * the JVM reads neither MP3 nor AAC.
 *
 * <p>The answer is the same {@link LiveTranscription.Segment} a live stream produces, so a meeting stores an uploaded
 * recording exactly as it stores one it heard.
 */
@Service
public class BatchTranscriptionService {
    private static final Logger LOG = LoggerFactory.getLogger(BatchTranscriptionService.class);
    /**
     * One recording at a time per process. The file is streamed, not held, but a provider call keeps a connection and
     * a worker thread busy for as long as a five-hour recording takes to come back.
     */
    private static final int MAX_CONCURRENT = 1;
    /** Providers transcribe faster than real time; this is the ceiling for a five-hour recording. */
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(45);
    private final VoiceConnectionService connections;
    private final VoiceAdapterRegistry adapters;
    private final @Nullable AiUsageRecorder usage;
    private final Semaphore running = new Semaphore(MAX_CONCURRENT);

    public BatchTranscriptionService(VoiceConnectionService connections, VoiceAdapterRegistry adapters,
                                     ObjectProvider<AiUsageRecorder> usage) {
        this.connections = connections;
        this.adapters = adapters;
        this.usage = usage.getIfAvailable();
    }

    /**
     * One uploaded recording, in the container the person chose. The audio is opened as the provider call sends it,
     * and {@code sizeBytes} is its exact length, which the upload declares to the provider.
     */
    public record Recording(AudioSource audio, long sizeBytes, String filename, String mediaType) {}

    /** What a recording became, and which connection read it, so the meeting can record both. */
    public record Transcribed(VoiceProvider provider, String model, boolean diarized,
                              List<LiveTranscription.Segment> segments) {}

    /**
     * A speech-to-text connection a member may transcribe a recording with, and what it will do with it: whether it
     * separates speakers and how large a file it takes. The limits are stated before a file is sent, not after.
     */
    public record Transcriber(UUID id, VoiceProvider provider, String model, boolean diarizes, long maxBytes,
                              boolean selected) {}

    /** The Tenant's connections that can transcribe an uploaded recording, the selected one first. */
    public List<Transcriber> transcribers(ActorId actor) {
        var all = connections.transcribers(actor);
        var listed = new ArrayList<Transcriber>(all.size());
        for (int i = 0; i < all.size(); i++) {
            var connection = all.get(i);
            if (!supports(connection.provider())) continue;
            listed.add(new Transcriber(connection.id(), connection.provider(), connection.sttModel(),
                    diarizes(connection.provider()), maxBytes(connection.provider()), i == 0));
        }
        return List.copyOf(listed);
    }

    /** Whether a provider can transcribe a whole uploaded file, as opposed to dictation only. */
    public boolean supports(VoiceProvider provider) { return adapters.transcribesRecordings(provider); }

    /** Only some providers separate speakers in a recording; elsewhere every sentence belongs to one speaker. */
    public boolean diarizes(VoiceProvider provider) { return adapters.diarizesRecordings(provider); }

    /** The largest recording a provider accepts; the others are bounded by what MemoryOS stores. */
    public long maxBytes(VoiceProvider provider) { return adapters.maxRecordingBytes(provider); }

    /**
     * Transcribes the recording with the named provider, or the Tenant's selected one when none is named, and adds
     * the audio to the AI usage ledger.
     */
    public Transcribed transcribe(ActorId actor, @Nullable VoiceProvider provider,
            LiveTranscription.Options options, Recording recording) {
        var connection = connections.transcriber(actor, provider);
        if (!supports(connection.provider()))
            throw VoiceException.invalid("This provider does not transcribe uploaded recordings.");
        if (recording.sizeBytes() > maxBytes(connection.provider()))
            throw VoiceException.invalid("The recording is larger than this provider accepts.");
        if (!running.tryAcquire()) throw VoiceException.busy();
        try {
            String key = connections.key(connection);
            boolean diarize = options.diarize() && diarizes(connection.provider());
            var adapter = adapters.batch(connection.provider()).orElseThrow(VoiceException::providerUnavailable);
            var segments = call(() -> adapter.segments(connection, key, options, diarize, recording, MAX_TIMEOUT));
            record(connection, actor, segments);
            return new Transcribed(connection.provider(), connection.sttModel(), diarize, segments);
        } finally {
            running.release();
        }
    }

    /** The recorded length the provider reported, not the file's size: compressed bytes say nothing about seconds. */
    private void record(VoiceConnectionService.Connection connection, ActorId actor,
            List<LiveTranscription.Segment> segments) {
        if (usage == null || segments.isEmpty()) return;
        double seconds = segments.getLast().endMs() / 1000.0;
        if (seconds <= 0) return;
        try {
            usage.record(new AiUsage(connection.tenantId(), actor.value(), AiUsageFlow.SPEECH_TO_TEXT,
                    connection.provider().name(), connection.sttModel(), connection.id(), null, null, 1, 0, 0, 0, 0,
                    seconds, null, Instant.now()));
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "voice.recording.usage_not_recorded")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Recording usage not recorded");
        }
    }

    @FunctionalInterface
    private interface ProviderCall {
        List<LiveTranscription.Segment> run() throws InterruptedException;
    }

    /** A provider failure of any kind is reported as unavailability; its detail never leaves this module. */
    private static List<LiveTranscription.Segment> call(ProviderCall call) {
        try {
            return call.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw VoiceException.providerUnavailable();
        } catch (RuntimeException failed) {
            if (failed instanceof VoiceException known) throw known;
            throw VoiceException.providerUnavailable();
        }
    }
}
