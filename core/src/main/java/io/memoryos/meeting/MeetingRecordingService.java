package io.memoryos.meeting;

import io.memoryos.voice.AudioSource;
import io.memoryos.voice.BatchTranscriptionService;
import io.memoryos.voice.LiveTranscription;
import io.memoryos.voice.VoiceProvider;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.LeasedJob;
import io.memoryos.shared.TenantId;
import io.memoryos.meeting.persistence.MeetingRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectUploadException;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadPurpose;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.UploadAuthorization;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A meeting made from a recording the owner uploaded instead of one MemoryOS heard. The file goes to object storage
 * through the same reservation the rest of the product uses, a leased job streams it to the speech provider in the
 * container it arrived in, and the next pass retires the recording once it has been transcribed or given up on.
 */
@Service
public class MeetingRecordingService {
    /** A recording that keeps failing stops being retried, as the minutes do. */
    static final int MAX_ATTEMPTS = 3;
    /** Longer than any provider call: a five-hour recording can take many minutes to come back. */
    static final Duration LEASE = Duration.ofMinutes(60);
    /** Recordings retired per pass; a backlog drains over the following passes. */
    static final int RETIRE_BATCH = 20;
    /** What retiring an upload that an earlier pass already retired answers. */
    private static final Set<String> RETIRED = Set.of("OBJECT_UPLOAD_CONFLICT", "OBJECT_UPLOAD_NOT_FOUND");
    /** Containers every supported provider reads; the media type is what the presigned upload pins. */
    private static final Set<String> MEDIA_TYPES = Set.of("audio/mpeg", "audio/mp3", "audio/mp4", "audio/m4a",
            "audio/x-m4a", "audio/wav", "audio/x-wav", "audio/wave", "audio/webm", "audio/ogg", "audio/flac",
            "video/mp4", "video/webm");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MeetingRecordingService.class);

    private final IamAuthorization authorization;
    private final MeetingRepository meetings;
    private final ObjectUploadService uploads;
    private final ObjectStorage storage;
    private final BatchTranscriptionService transcription;
    private final MeetingService service;
    private final TransactionTemplate tx;

    public MeetingRecordingService(IamAuthorization authorization, MeetingRepository meetings,
            ObjectUploadService uploads, ObjectStorage storage, BatchTranscriptionService transcription,
            MeetingService service, PlatformTransactionManager transactions) {
        this.authorization = authorization;
        this.meetings = meetings;
        this.uploads = uploads;
        this.storage = storage;
        this.transcription = transcription;
        this.service = service;
        this.tx = new TransactionTemplate(transactions);
    }

    /** What the browser declares before it uploads; the bytes are checked against it afterwards. */
    public record Upload(String filename, String mediaType, long sizeBytes, String sha256,
                         @Nullable VoiceProvider provider) {}

    /** Where to send the bytes, together with the meeting as it now reads. */
    public record Reservation(Meeting.Detail meeting, UploadAuthorization upload) {}

    /** Reserves storage for a recording of an owned meeting that has not recorded anything yet. */
    @Transactional
    public Reservation reserve(ActorId actor, UUID id, Upload upload) {
        UUID tenant = tenant(actor);
        var meeting = meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        if (meeting.status() != Meeting.Status.RECORDING) throw MeetingException.ended();
        if (meetings.hasUtterances(tenant, id))
            throw MeetingException.invalid("This meeting already has a transcript.");
        var available = transcription.transcribers(actor);
        if (available.isEmpty())
            throw MeetingException.invalid("No speech connection in this Tenant transcribes a recording.");
        if (upload.provider() != null && available.stream().noneMatch(t -> t.provider() == upload.provider()))
            throw MeetingException.invalid("That provider does not transcribe a recording.");
        var specification = new ObjectUploadSpecification(filename(upload.filename()), mediaType(upload.mediaType()),
                size(upload.sizeBytes(), upload.provider()), new ContentSha256(upload.sha256()),
                ObjectUploadPurpose.MEETING_AUDIO);
        var authorized = uploads.initiate(new TenantId(tenant), specification);
        meetings.reserveAudio(tenant, id, authorized.uploadId().value(), specification.filename(),
                specification.mediaType(), specification.sizeBytes(),
                upload.provider() == null ? null : upload.provider().name());
        return new Reservation(service.get(actor, id), authorized.authorization());
    }

    /** Accepts the uploaded bytes and queues the recording for transcription. */
    public Meeting.Detail finalizeUpload(ActorId actor, UUID id) {
        UUID tenant = tenant(actor);
        var waiting = service.owned(actor, id);
        if (waiting.audio().status() != Meeting.AudioStatus.WAITING)
            throw MeetingException.invalid("No recording is waiting for this meeting.");
        UUID uploadId = meetings.audioUpload(tenant, id).orElseThrow(MeetingException::notFound);
        // Verifying reads object storage; it runs before the meeting row is locked, as a chat file upload does.
        var verified = uploads.verify(new TenantId(tenant), new ObjectUploadId(uploadId),
                ObjectUploadPurpose.MEETING_AUDIO);
        tx.executeWithoutResult(ignored -> {
            meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
            uploads.adopt(new TenantId(tenant), verified.uploadId(), verified.token());
            if (!meetings.queueAudio(tenant, id, verified.object().key().value()))
                throw MeetingException.invalid("No recording is waiting for this meeting.");
        });
        return service.get(actor, id);
    }

    /**
     * Retires the recordings that are done with, then transcribes the oldest one waiting. Returns whether one was
     * claimed.
     */
    public boolean transcribeNext() {
        retireHeld();
        return LeasedJob.runNext(LOG, "meeting.recording", new LeasedJob.Steps<>(
                () -> Objects.requireNonNull(tx.execute(ignored -> meetings.failAbandonedAudio(MAX_ATTEMPTS))),
                () -> Objects.requireNonNull(tx.execute(ignored -> meetings.claimAudio(LEASE, MAX_ATTEMPTS))),
                this::transcribe,
                (claim, failure) -> tx.executeWithoutResult(ignored -> meetings.failAudio(claim.tenant(), claim.id(),
                        claim.attempts(), MAX_ATTEMPTS, reason(failure)))));
    }

    /**
     * Retires the bytes of every recording that has been transcribed or given up on. This is the one path that does:
     * a transcript, a failed last attempt and a lapsed last lease all commit first and leave the upload on the meeting
     * row, so a process that stops before the bytes go leaves the row for the next pass rather than an adopted upload
     * that nothing would ever retire. Returns how many were retired.
     */
    public int retireHeld() {
        int retired = 0;
        for (var held : meetings.heldRecordings(RETIRE_BATCH))
            if (retire(held.tenant(), held.id(), held.uploadId())) retired++;
        return retired;
    }

    private void transcribe(MeetingRepository.AudioClaim claim) {
        var meeting = meetings.find(claim.tenant(), claim.owner(), claim.id()).orElse(null);
        // The owner deleted the meeting while it waited; its bytes were retired with it.
        if (meeting == null) return;
        var owner = new ActorId(claim.owner());
        var provider = claim.provider() == null ? null : VoiceProvider.valueOf(claim.provider());
        var options = new LiveTranscription.Options(meeting.language(), meeting.terms(), true);
        BatchTranscriptionService.Transcribed transcribed;
        try (var recording = new StoredRecording(claim.key())) {
            transcribed = transcription.transcribe(owner, provider, options, new BatchTranscriptionService.Recording(
                    recording, claim.sizeBytes(), claim.filename(), claim.mediaType()));
        }
        if (transcribed.segments().isEmpty()) throw MeetingException.invalid("The recording carried no speech.");
        var utterances = transcribed.segments().stream()
                .map(segment -> new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, segment.speaker(),
                        segment.startMs(), Math.max(segment.endMs(), segment.startMs()), segment.text(),
                        Math.clamp(segment.confidence(), 0, 1),
                        segment.spans().stream().map(span -> new Meeting.Span(span.start(), span.end(),
                                Math.clamp(span.confidence(), 0, 1))).toList()))
                .toList();
        boolean stored = Boolean.TRUE.equals(tx.execute(ignored -> {
            boolean written = meetings.writeAudio(claim.tenant(), claim.id(), claim.attempts(),
                    transcribed.provider().name(), transcribed.model(), transcribed.diarized(), utterances);
            if (written) meetings.queueMinutes(claim.tenant(), claim.id());
            return written;
        }));
        // The bytes go with the next pass's sweep. Another replica that took the recording over after this lease
        // lapsed owns the outcome.
        if (!stored) LOG.warn("Meeting recording lease lapsed before its transcript was stored");
    }

    /**
     * The recording in object storage, opened as the provider call sends it and closed when the call returns. The
     * first opening happens here, so a missing object is reported as an unreadable recording rather than as a
     * provider that did not answer.
     */
    private final class StoredRecording implements AudioSource, AutoCloseable {
        private final ObjectKey key;
        private final List<ObjectContent> opened = new ArrayList<>();
        private @Nullable ObjectContent first;

        private StoredRecording(String key) {
            this.key = new ObjectKey(key);
            ObjectContent content;
            try {
                content = storage.open(this.key);
            } catch (RuntimeException unreadable) {
                throw MeetingException.invalid("The recording could not be read.");
            }
            first = content;
            opened.add(content);
        }

        @Override
        public synchronized InputStream open() {
            var content = first;
            first = null;
            if (content == null) {
                content = storage.open(key);
                opened.add(content);
            }
            return content.inputStream();
        }

        @Override
        public synchronized void close() {
            for (var content : opened) {
                try {
                    content.close();
                } catch (RuntimeException ignored) {
                    // The provider call has finished with it; a failed close loses nothing.
                }
            }
            opened.clear();
        }
    }

    /**
     * Retires one recording's bytes, then forgets them on the meeting. An upload that is already retired is only
     * forgotten. Returns whether the meeting no longer holds it; any other failure leaves it for the next sweep.
     */
    private boolean retire(UUID tenant, UUID meeting, UUID uploadId) {
        try {
            uploads.retireAdopted(new TenantId(tenant), new ObjectUploadId(uploadId));
        } catch (ObjectUploadException gone) {
            // Retired by an earlier pass that stopped before forgetting it: nothing is left to retire.
            if (!RETIRED.contains(gone.code())) return failedToRetire("error_code", gone.code());
        } catch (RuntimeException failure) {
            return failedToRetire("error_type", failure.getClass().getName());
        }
        tx.executeWithoutResult(ignored -> meetings.forgetAudio(tenant, meeting, uploadId));
        return true;
    }

    private static boolean failedToRetire(String field, String value) {
        LOG.atWarn().addKeyValue("event", "meeting.recording.retire_failed").addKeyValue(field, value)
                .log("Meeting recording not retired; the next pass tries again");
        return false;
    }

    /**
     * Deletes a meeting and the recording it still holds; the bytes never outlive the meeting. They are retired first:
     * once the row is gone nothing points at them, while a meeting whose delete did not happen can be deleted again.
     */
    public void delete(ActorId actor, UUID id) {
        UUID tenant = tenant(actor);
        var meeting = service.owned(actor, id);
        UUID upload = meetings.audioUpload(tenant, id).orElse(null);
        boolean adopted = meeting.audio().status() != Meeting.AudioStatus.NONE
                && meeting.audio().status() != Meeting.AudioStatus.WAITING;
        // A reservation the browser never filled was not adopted; the abandoned-upload cleanup owns it.
        // Refused rather than half done: once the row is gone, nothing would point the sweep at the bytes.
        if (adopted && upload != null && !retire(tenant, id, upload)) throw MeetingException.conflict();
        service.delete(actor, id);
    }

    private static String reason(RuntimeException failure) {
        if (failure instanceof io.memoryos.BusinessException business) return business.code();
        return "MEETING_RECORDING_FAILED";
    }

    private static String filename(@Nullable String filename) {
        String name = filename == null ? "" : filename.strip();
        if (name.isEmpty() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl))
            throw MeetingException.invalid("A recording needs a file name.");
        return name;
    }

    private static String mediaType(@Nullable String mediaType) {
        String type = mediaType == null ? "" : mediaType.strip().toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPES.contains(type))
            throw MeetingException.invalid("Upload an MP3, M4A, WAV, WebM, OGG, FLAC or MP4 recording.");
        return type;
    }

    private static long size(long sizeBytes, @Nullable VoiceProvider provider) {
        long limit = provider == null ? ObjectUploadPurpose.MEETING_AUDIO.maximumBytes()
                : Math.min(BatchTranscriptionService.maxBytes(provider), ObjectUploadPurpose.MEETING_AUDIO.maximumBytes());
        if (sizeBytes < 1 || sizeBytes > limit)
            throw MeetingException.invalid("The recording is larger than this provider accepts.");
        return sizeBytes;
    }

    /** The transcribers a member may choose between for their own recording. */
    @Transactional(readOnly = true)
    public List<BatchTranscriptionService.Transcriber> transcribers(ActorId actor) {
        tenant(actor);
        return transcription.transcribers(actor);
    }

    private UUID tenant(ActorId actor) {
        return authorization.require(actor, IamCapability.CHAT_WRITE, false).tenantId().value();
    }
}
