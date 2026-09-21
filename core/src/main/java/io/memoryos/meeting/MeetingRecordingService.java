package io.memoryos.meeting;

import io.memoryos.chat.voice.BatchTranscriptionService;
import io.memoryos.chat.voice.LiveTranscription;
import io.memoryos.chat.voice.VoiceProvider;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.meeting.persistence.MeetingRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadPurpose;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.UploadAuthorization;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A meeting made from a recording the owner uploaded instead of one MemoryOS heard. The file goes to object storage
 * through the same reservation the rest of the product uses, a leased job hands it to the speech provider in the
 * container it arrived in, and the recording is retired as soon as it has been transcribed or given up on.
 */
@Service
public class MeetingRecordingService {
    /** A recording that keeps failing stops being retried, as the minutes do. */
    static final int MAX_ATTEMPTS = 3;
    /** Longer than any provider call: a five-hour recording can take many minutes to come back. */
    static final Duration LEASE = Duration.ofMinutes(60);
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
        if (!meetings.utterances(tenant, id).isEmpty())
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
        var waiting = service.get(actor, id);
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

    /** Transcribes the oldest recording waiting for it. Returns whether one was claimed. */
    public boolean transcribeNext() {
        var claimed = tx.execute(ignored -> meetings.claimAudio(LEASE, MAX_ATTEMPTS).orElse(null));
        if (claimed == null) return false;
        try {
            transcribe(claimed);
        } catch (RuntimeException failure) {
            LOG.warn("Meeting recording failed on attempt {} ({})", claimed.attempts(), failure.getClass().getSimpleName());
            tx.executeWithoutResult(ignored -> meetings.failAudio(claimed.tenant(), claimed.id(), claimed.attempts(),
                    MAX_ATTEMPTS, reason(failure)));
            if (claimed.attempts() >= MAX_ATTEMPTS) retire(claimed.tenant(), claimed.id(), claimed.uploadId());
        }
        return true;
    }

    private void transcribe(MeetingRepository.AudioClaim claim) {
        var meeting = meetings.find(claim.tenant(), claim.owner(), claim.id()).orElse(null);
        // The owner deleted the meeting while it waited; its bytes are retired with it.
        if (meeting == null) return;
        var owner = new ActorId(claim.owner());
        var provider = claim.provider() == null ? null : VoiceProvider.valueOf(claim.provider());
        var options = new LiveTranscription.Options(meeting.language(), meeting.terms(), true);
        var transcribed = transcription.transcribe(owner, provider, options,
                new BatchTranscriptionService.Recording(read(claim), claim.filename(), claim.mediaType()));
        if (transcribed.segments().isEmpty()) throw MeetingException.invalid("The recording carried no speech.");
        var utterances = transcribed.segments().stream()
                .map(segment -> new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, segment.speaker(),
                        segment.startMs(), Math.max(segment.endMs(), segment.startMs()), segment.text(),
                        Math.clamp(segment.confidence(), 0, 1)))
                .toList();
        boolean stored = Boolean.TRUE.equals(tx.execute(ignored -> {
            boolean written = meetings.writeAudio(claim.tenant(), claim.id(), claim.attempts(),
                    transcribed.provider().name(), transcribed.model(), transcribed.diarized(), utterances);
            if (written) meetings.queueMinutes(claim.tenant(), claim.id());
            return written;
        }));
        // Another replica took the recording over after this lease lapsed; it owns the outcome and the bytes.
        if (stored) retire(claim.tenant(), claim.id(), claim.uploadId());
        else LOG.warn("Meeting recording lease lapsed before its transcript was stored");
    }

    /** Reads the whole recording; a provider call needs it as one body, and the reservation bounds its size. */
    private byte[] read(MeetingRepository.AudioClaim claim) {
        try (var content = storage.open(new ObjectKey(claim.key()))) {
            return content.inputStream().readNBytes((int) Math.min(claim.sizeBytes(), Integer.MAX_VALUE));
        } catch (IOException | RuntimeException unreadable) {
            throw MeetingException.invalid("The recording could not be read.");
        }
    }

    /** Deletes the recording's bytes. Called once its transcript is stored, once it is given up on, and on delete. */
    public void retire(UUID tenant, UUID meeting, @Nullable UUID uploadId) {
        if (uploadId == null) return;
        try {
            uploads.retireAdopted(new TenantId(tenant), new ObjectUploadId(uploadId));
            tx.executeWithoutResult(ignored -> meetings.forgetAudio(tenant, meeting));
        } catch (RuntimeException failure) {
            // The abandoned-upload cleanup still owns the bytes; a failure here must not lose the transcript.
            LOG.warn("Meeting recording not retired ({})", failure.getClass().getSimpleName());
        }
    }

    /** Deletes a meeting and the recording it still holds; the bytes never outlive the meeting. */
    public void delete(ActorId actor, UUID id) {
        UUID tenant = tenant(actor);
        var meeting = service.get(actor, id);
        UUID upload = meetings.audioUpload(tenant, id).orElse(null);
        boolean adopted = meeting.audio().status() != Meeting.AudioStatus.NONE
                && meeting.audio().status() != Meeting.AudioStatus.WAITING;
        service.delete(actor, id);
        // A reservation the browser never filled was not adopted; the abandoned-upload cleanup owns it.
        if (adopted) retire(tenant, id, upload);
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
