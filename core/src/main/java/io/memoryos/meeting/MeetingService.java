package io.memoryos.meeting;

import io.memoryos.chat.voice.LiveTranscription;
import io.memoryos.chat.voice.LiveTranscriptionService;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.meeting.persistence.MeetingRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meetings. Recording, reading and editing need Chat write access in the active Tenant. A meeting belongs to the
 * member who recorded it: only they rename a speaker, write notes, end it, rerun its minutes, share it or delete it.
 * Everyone they shared it with reads the transcript, the minutes and the biên bản, and nothing else. A meeting that
 * reaches neither answers as if it did not exist.
 */
@Service
public class MeetingService {
    /** Soniox accepts at most 300 minutes of audio per stream; one track never exceeds that. */
    public static final Duration MAX_TRACK = Duration.ofHours(5);
    static final int MAX_LIST = 200;
    static final int MAX_PARTICIPANTS = 50;
    static final int MAX_TERMS = 100;
    static final int MAX_NAME = 200;
    static final int MAX_TERM = 100;
    static final int MAX_SUMMARY = 20_000;
    static final int MAX_ITEM = 2_000;
    static final int MAX_DUE = 100;
    /** Decisions or pieces of work of one kind; a biên bản with more has stopped being read. */
    static final int MAX_ITEMS = 100;
    /** Marks a person leaves for themselves; past this many they are no longer marking anything out. */
    static final int MAX_BOOKMARKS = 200;
    static final int MAX_NOTES = 50_000;
    /** A meeting is shared with people who were in it, not broadcast; the bound keeps the list readable. */
    static final int MAX_READERS = 200;
    /** The subject line of a biên bản; long enough for a sentence, short enough to print. */
    static final int MAX_NOTES_LINE = 500;
    private static final Set<String> LANGUAGES = Set.of("vi", "en");
    private static final long BYTES_PER_SECOND = 48_000;
    private final IamAuthorization authorization;
    private final MeetingRepository meetings;
    private final LiveTranscriptionService live;

    public MeetingService(IamAuthorization authorization, MeetingRepository meetings, LiveTranscriptionService live) {
        this.authorization = authorization;
        this.meetings = meetings;
        this.live = live;
    }

    /** Receives a track's live events; implementations must be quick and thread-safe. */
    public interface TrackListener {
        void preview(String speaker, String text);

        void utterance(Meeting.Utterance utterance);

        /** The speech provider stopped after retries; stored utterances remain. */
        void failed();
    }

    /** One audio track being recorded into a meeting. */
    public interface TrackSession extends AutoCloseable {
        /** Appends whole PCM16 24 kHz mono samples. */
        void append(byte[] pcm);

        /** Completes once every utterance of the audio received so far is stored. */
        CompletableFuture<Void> finish();

        @Override
        void close();
    }

    @Transactional
    public Meeting.Detail create(ActorId actor, Meeting.Draft draft) {
        UUID tenant = tenant(actor);
        var clean = validate(draft);
        UUID id = UUID.randomUUID();
        meetings.insert(tenant, id, actor.value(), clean);
        return detail(tenant, actor, id);
    }

    @Transactional(readOnly = true)
    public List<Meeting.Summary> list(ActorId actor) {
        return meetings.list(tenant(actor), actor.value(), MAX_LIST);
    }

    @Transactional(readOnly = true)
    public Meeting.Detail get(ActorId actor, UUID id) {
        return readable(tenant(actor), actor, id);
    }

    /** Names a diarized voice; a blank name restores the automatic label. */
    @Transactional
    public Meeting.Detail nameSpeaker(ActorId actor, UUID id, Meeting.Track track, String label, @Nullable String name) {
        UUID tenant = tenant(actor);
        meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        String clean = name == null || name.isBlank() ? null : name.strip();
        if (clean != null && (clean.length() > MAX_NAME || clean.chars().anyMatch(Character::isISOControl)))
            throw MeetingException.invalid("A speaker name has 1 to 200 characters.");
        if (!meetings.nameSpeaker(tenant, id, track, label, clean)) throw MeetingException.notFound();
        return detail(tenant, actor, id);
    }

    /** Keeps the automatic label and stops offering the name this voice gave itself. */
    @Transactional
    public Meeting.Detail dismissSpeakerSuggestion(ActorId actor, UUID id, Meeting.Track track, String label) {
        UUID tenant = tenant(actor);
        meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        if (!meetings.dismissSuggestion(tenant, id, track, label)) throw MeetingException.notFound();
        return detail(tenant, actor, id);
    }

    @Transactional
    public Meeting.Detail updateNotes(ActorId actor, UUID id, String notes, long revision) {
        UUID tenant = tenant(actor);
        var meeting = meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        if (meeting.revision() != revision) throw MeetingException.conflict();
        if (notes == null || notes.length() > MAX_NOTES) throw MeetingException.invalid("Notes have at most 50,000 characters.");
        meetings.updateNotes(tenant, id, notes);
        return detail(tenant, actor, id);
    }

    /**
     * Renames the meeting and replaces who was in it, at any time: the start form only asks what the recording needs,
     * so these are filled in once the meeting is under way. Kind, language and terms stay as recorded.
     */
    @Transactional
    public Meeting.Detail updateDetails(ActorId actor, UUID id, String title, @Nullable List<String> participants) {
        UUID tenant = tenant(actor);
        var meeting = meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        var clean = validate(new Meeting.Draft(title, meeting.kind(), meeting.language(), participants, List.of()));
        meetings.updateDetails(tenant, id, clean.title(), clean.participants());
        return detail(tenant, actor, id);
    }

    /** The biên bản heading as the owner last saved it; a reader who exports starts from the owner's. */
    @Transactional(readOnly = true)
    public Optional<MeetingMinutesDocument.Heading> heading(ActorId actor, UUID id) {
        UUID tenant = tenant(actor);
        readable(tenant, actor, id);
        return meetings.heading(tenant, id);
    }

    /**
     * What a meeting with no heading of its own starts from: the organization, its parent and the typeface of the
     * caller's last biên bản. They rarely change between meetings; the number, place and people always do.
     */
    @Transactional(readOnly = true)
    public Optional<MeetingMinutesDocument.Heading> carriedHeading(ActorId actor) {
        return meetings.lastHeading(tenant(actor), actor.value())
                .map(last -> new MeetingMinutesDocument.Heading(last.organization(), last.parentOrganization(), "",
                        "", "", "", "", "", "", "", "", List.of(), last.font()));
    }

    /** Keeps the heading the owner typed, so the export opens where they stopped. */
    @Transactional
    public MeetingMinutesDocument.Heading saveHeading(ActorId actor, UUID id, MeetingMinutesDocument.Heading heading) {
        UUID tenant = tenant(actor);
        meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        var clean = validate(heading);
        meetings.saveHeading(tenant, id, clean);
        return clean;
    }

    /** Ends recording and queues the minutes. Ending twice is harmless; streams still open are closed by their sockets. */
    @Transactional
    public Meeting.Detail end(ActorId actor, UUID id) {
        UUID tenant = tenant(actor);
        var meeting = meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        // A recording being transcribed ends itself when the job finishes; ending it here would lose it.
        if (meeting.status() == Meeting.Status.TRANSCRIBING)
            throw MeetingException.invalid("The recording is still being transcribed.");
        meetings.end(tenant, id);
        // A meeting nobody spoke in has nothing to summarize.
        if (meeting.status() == Meeting.Status.RECORDING && !meetings.utterances(tenant, id).isEmpty())
            meetings.queueMinutes(tenant, id);
        return detail(tenant, actor, id);
    }

    /** Runs the minutes again, for a meeting whose transcript or notes changed after the first run. */
    @Transactional
    public Meeting.Detail rerunMinutes(ActorId actor, UUID id, boolean discardEdits) {
        UUID tenant = tenant(actor);
        var meeting = meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        if (meeting.status() != Meeting.Status.ENDED) throw MeetingException.invalid("The meeting is still recording.");
        if (meetings.utterances(tenant, id).isEmpty()) throw MeetingException.invalid("This meeting has no transcript.");
        // A rerun writes the whole minutes again, so it throws away whatever the owner corrected by hand. They have
        // to say they mean that, rather than find their work gone.
        if (meeting.minutesEdited() && !discardEdits) throw MeetingException.conflict();
        meetings.queueMinutes(tenant, id);
        return detail(tenant, actor, id);
    }

    /** Rewrites the summary in the owner's own words. What the model wrote stays in the events beside it. */
    @Transactional
    public Meeting.Detail editSummary(ActorId actor, UUID id, String summary) {
        UUID tenant = tenant(actor);
        var meeting = meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        if (meeting.minutesStatus() != Meeting.MinutesStatus.READY)
            throw MeetingException.invalid("The minutes are not written yet.");
        String clean = text(summary, MAX_SUMMARY, "A summary");
        if (!clean.equals(meeting.minutesSummary())) {
            meetings.recordMinutesEdit(tenant, id, null, Meeting.MinutesField.SUMMARY, actor.value(),
                    meeting.minutesSummary(), clean);
            meetings.rewriteSummary(tenant, id, clean);
        }
        return detail(tenant, actor, id);
    }

    /** Rewrites one decision or one piece of work: what it says, who owns it, when it is due. */
    @Transactional
    public Meeting.Detail editItem(ActorId actor, UUID id, UUID itemId, String itemText, @Nullable String owner,
            @Nullable String due) {
        UUID tenant = tenant(actor);
        meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        var item = meetings.lockItem(tenant, id, itemId).orElseThrow(MeetingException::notFound);
        String cleanText = text(itemText, MAX_ITEM, "An item");
        String cleanOwner = optional(owner, MAX_NAME, "An owner");
        String cleanDue = optional(due, MAX_DUE, "A deadline");
        // A decision belongs to the meeting, not to a person, so it carries neither an owner nor a deadline.
        if (item.kind() != Meeting.ItemKind.ACTION && (cleanOwner != null || cleanDue != null))
            throw MeetingException.invalid("A decision has no owner and no deadline.");
        record Change(Meeting.MinutesField field, String before, String after) {}
        var changes = new ArrayList<Change>(3);
        if (!cleanText.equals(item.text()))
            changes.add(new Change(Meeting.MinutesField.TEXT, item.text(), cleanText));
        if (!java.util.Objects.equals(cleanOwner, item.owner()))
            changes.add(new Change(Meeting.MinutesField.OWNER, item.owner() == null ? "" : item.owner(),
                    cleanOwner == null ? "" : cleanOwner));
        if (!java.util.Objects.equals(cleanDue, item.due()))
            changes.add(new Change(Meeting.MinutesField.DUE, item.due() == null ? "" : item.due(),
                    cleanDue == null ? "" : cleanDue));
        if (changes.isEmpty()) return detail(tenant, actor, id);
        for (var change : changes)
            meetings.recordMinutesEdit(tenant, id, itemId, change.field(), actor.value(), change.before(),
                    change.after());
        meetings.rewriteItem(tenant, id, new Meeting.MinutesItem(item.id(), item.kind(), cleanText, cleanOwner,
                cleanDue, item.quote(), item.sourceUtteranceId(), item.done(), true));
        return detail(tenant, actor, id);
    }

    /**
     * Writes in a decision or a piece of work the model missed. Its history starts with the owner's own words, as an
     * event from nothing, so the record still says who wrote it.
     */
    @Transactional
    public Meeting.Detail addItem(ActorId actor, UUID id, Meeting.@Nullable ItemKind kind, String itemText,
            @Nullable String owner, @Nullable String due) {
        UUID tenant = tenant(actor);
        var meeting = meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        if (meeting.minutesStatus() != Meeting.MinutesStatus.READY)
            throw MeetingException.invalid("The minutes are not written yet.");
        if (kind != Meeting.ItemKind.DECISION && kind != Meeting.ItemKind.ACTION)
            throw MeetingException.invalid("Only a decision or a piece of work can be added.");
        String cleanText = text(itemText, MAX_ITEM, "An item");
        String cleanOwner = optional(owner, MAX_NAME, "An owner");
        String cleanDue = optional(due, MAX_DUE, "A deadline");
        if (kind == Meeting.ItemKind.DECISION && (cleanOwner != null || cleanDue != null))
            throw MeetingException.invalid("A decision has no owner and no deadline.");
        if (meetings.minutesItems(tenant, id).stream().filter(item -> item.kind() == kind).count() >= MAX_ITEMS)
            throw MeetingException.invalid("The minutes hold at most " + MAX_ITEMS + " items of a kind.");
        UUID itemId = UUID.randomUUID();
        meetings.addItem(tenant, id, new Meeting.MinutesItem(itemId, kind, cleanText, cleanOwner, cleanDue, null,
                null, false, true));
        meetings.recordMinutesEdit(tenant, id, itemId, Meeting.MinutesField.TEXT, actor.value(), "", cleanText);
        return detail(tenant, actor, id);
    }

    /** Takes out a decision or a piece of work that should not be in the minutes; its words stay in the events. */
    @Transactional
    public Meeting.Detail removeItem(ActorId actor, UUID id, UUID itemId) {
        UUID tenant = tenant(actor);
        meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        var item = meetings.lockItem(tenant, id, itemId).orElseThrow(MeetingException::notFound);
        if (item.kind() == Meeting.ItemKind.TOPIC) throw MeetingException.notFound();
        meetings.recordMinutesEdit(tenant, id, itemId, Meeting.MinutesField.TEXT, actor.value(), item.text(), "");
        meetings.removeItem(tenant, id, itemId);
        return detail(tenant, actor, id);
    }

    private static String text(@Nullable String value, int limit, String what) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty() || clean.length() > limit)
            throw MeetingException.invalid(what + " has 1 to " + limit + " characters.");
        return clean;
    }

    private static @Nullable String optional(@Nullable String value, int limit, String what) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) return null;
        if (clean.length() > limit || clean.chars().anyMatch(Character::isISOControl))
            throw MeetingException.invalid(what + " has at most " + limit + " characters.");
        return clean;
    }

    /** Renders the minutes as a Vietnamese biên bản in Word format. Nothing is stored; the heading comes with the call. */
    @Transactional(readOnly = true)
    /** What the transcript is downloaded as. Both say the same thing; one is for editing and one for reading. */
    public enum TranscriptFormat { DOCX, PDF }

    /**
     * The transcript itself, for anybody who can read the meeting. This is not the biên bản: it is what was said,
     * with the time and the speaker, and nothing arranged around it.
     */
    @Transactional(readOnly = true)
    public byte[] exportTranscript(ActorId actor, UUID id, TranscriptFormat format) {
        var meeting = readable(tenant(actor), actor, id);
        if (meeting.utterances().isEmpty()) throw MeetingException.invalid("This meeting has no transcript yet.");
        return format == TranscriptFormat.PDF
                ? MeetingTranscriptPdf.render(meeting)
                : MeetingTranscriptDocument.render(meeting);
    }

    public byte[] exportMinutes(ActorId actor, UUID id, MeetingMinutesDocument.Heading heading) {
        return exportMinutes(actor, id, heading, TranscriptFormat.DOCX);
    }

    /** The biên bản as Word to edit or as PDF to read and print; both say exactly the same thing. */
    public byte[] exportMinutes(ActorId actor, UUID id, MeetingMinutesDocument.Heading heading,
            TranscriptFormat format) {
        UUID tenant = tenant(actor);
        var meeting = readable(tenant, actor, id);
        if (meeting.minutes().status() != Meeting.MinutesStatus.READY)
            throw MeetingException.invalid("The minutes are not written yet.");
        var clean = validate(heading);
        return format == TranscriptFormat.PDF
                ? MeetingMinutesPdf.render(meeting, clean)
                : MeetingMinutesDocument.render(meeting, clean);
    }

    /** The heading is printed, not stored, so it only has to fit on the page. */
    static MeetingMinutesDocument.Heading validate(MeetingMinutesDocument.@Nullable Heading heading) {
        if (heading == null) throw MeetingException.invalid("The minutes need a heading.");
        var attendees = new LinkedHashSet<String>();
        for (var attendee : heading.attendees()) {
            String name = attendee == null ? "" : attendee.strip();
            if (!name.isEmpty()) attendees.add(field(name, MAX_NAME, "An attendee"));
            if (attendees.size() > MAX_PARTICIPANTS)
                throw MeetingException.invalid("A meeting has at most 50 attendees.");
        }
        return new MeetingMinutesDocument.Heading(field(heading.organization(), MAX_NAME, "The organization"),
                field(heading.parentOrganization(), MAX_NAME, "The parent organization"),
                field(heading.number(), MAX_TERM, "The number"), field(heading.about(), MAX_NOTES_LINE, "The subject"),
                field(heading.place(), MAX_NAME, "The place"), field(heading.opened(), MAX_NAME, "The opening time"),
                field(heading.closed(), MAX_NAME, "The closing time"), field(heading.chair(), MAX_NAME, "The chair"),
                field(heading.chairRole(), MAX_NAME, "The chair's role"),
                field(heading.secretary(), MAX_NAME, "The secretary"),
                field(heading.secretaryRole(), MAX_NAME, "The secretary's role"), List.copyOf(attendees),
                typeface(heading.font()));
    }

    /**
     * The typefaces a biên bản may be set in. Word only names the face and the reader's machine supplies it, so this
     * is a list of faces every office machine has rather than a list of files; anything else falls back to the
     * decree's own.
     */
    static final List<String> TYPEFACES = List.of("Times New Roman", "Arial", "Calibri", "Tahoma");

    private static String typeface(@Nullable String font) {
        String clean = font == null ? "" : font.strip();
        return TYPEFACES.contains(clean) ? clean : "";
    }

    private static String field(@Nullable String value, int limit, String what) {
        String text = value == null ? "" : value.strip();
        if (text.length() > limit) throw MeetingException.invalid(what + " is too long.");
        return text;
    }

    /**
     * Replaces who may read this meeting. Only its owner may say. A person who is not an active member, or a Group
     * that is not the Tenant's, is refused rather than dropped: the owner must see that the meeting did not reach
     * who they meant, as sharing an Agent or a Document Set does.
     */
    @Transactional
    public Meeting.Detail share(ActorId actor, UUID id, List<UUID> members, List<UUID> groups) {
        UUID tenant = tenant(actor);
        meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        var people = distinct(members, "members");
        var teams = distinct(groups, "Groups");
        // The owner already reads their own meeting; naming themselves would be noise in the list.
        people.remove(actor.value());
        var named = meetings.members(tenant, List.copyOf(people));
        if (named.size() != people.size())
            throw MeetingException.invalid("A chosen person is not an active member.");
        var teamsNamed = meetings.groups(tenant, List.copyOf(teams));
        if (teamsNamed.size() != teams.size()) throw MeetingException.invalid("A chosen Group is unavailable.");
        meetings.share(tenant, id, named, teamsNamed);
        return detail(tenant, actor, id);
    }

    private static LinkedHashSet<UUID> distinct(@Nullable List<UUID> ids, String what) {
        var unique = new LinkedHashSet<UUID>();
        for (var id : ids == null ? List.<UUID>of() : ids) if (id != null) unique.add(id);
        if (unique.size() > MAX_READERS)
            throw MeetingException.invalid("A meeting is shared with at most 200 " + what + ".");
        return unique;
    }

    /** Ticks off a task the minutes found. */
    @Transactional
    public Meeting.Detail markItem(ActorId actor, UUID id, UUID item, boolean done) {
        UUID tenant = tenant(actor);
        meetings.lock(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        if (!meetings.markItem(tenant, id, item, done)) throw MeetingException.notFound();
        return detail(tenant, actor, id);
    }

    @Transactional
    public void delete(ActorId actor, UUID id) {
        if (!meetings.delete(tenant(actor), actor.value(), id)) throw MeetingException.notFound();
    }

    /** Authorizes the owner before a live socket opens, so a refused handshake reveals nothing. */
    @Transactional(readOnly = true)
    public void requireRecordable(ActorId actor, UUID id, Meeting.Track track) {
        var meeting = meetings.find(tenant(actor), actor.value(), id).orElseThrow(MeetingException::notFound);
        requireRecordable(meeting, track);
    }

    /**
     * Opens one track. {@code offsetMs} is the recording time of the first sample, so a browser that reconnects
     * continues the meeting's clock. Utterances are stored as the provider commits them.
     */
    public TrackSession openTrack(ActorId actor, UUID id, Meeting.Track track, long offsetMs, TrackListener listener) {
        UUID tenant = tenant(actor);
        var meeting = meetings.find(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        requireRecordable(meeting, track);
        if (offsetMs < 0 || offsetMs >= MAX_TRACK.toMillis()) throw MeetingException.tooLong();
        // Online, the microphone carries only the owner; everything else is diarized into speakers.
        boolean diarize = !(meeting.kind() == Meeting.Kind.ONLINE && track == Meeting.Track.MIC);
        var options = new LiveTranscription.Options(meeting.language(), meeting.terms(), diarize);
        var opened = live.open(actor, options, offsetMs, new LiveTranscription.Listener() {
            @Override
            public void preview(String speaker, String text) {
                listener.preview(speaker, text);
            }

            @Override
            public void segment(LiveTranscription.Segment segment) {
                if (segment.text().isBlank()) return;
                var utterance = new Meeting.Utterance(UUID.randomUUID(), track, speaker(segment.speaker()),
                        segment.startMs(), Math.max(segment.startMs(), segment.endMs()), trim(segment.text()),
                        Math.clamp(segment.confidence(), 0, 1), spans(segment));
                try {
                    meetings.insertUtterance(tenant, id, utterance);
                } catch (RuntimeException gone) {
                    // The meeting was deleted while recording; nothing more can be stored.
                    listener.failed();
                    return;
                }
                listener.utterance(utterance);
            }

            @Override
            public void failed() {
                listener.failed();
            }
        });
        meetings.recordProvider(tenant, id, opened.provider(), opened.model(), opened.diarizes());
        var stream = opened.stream();
        var bytes = new AtomicLong();
        long budget = (MAX_TRACK.toMillis() - offsetMs) * BYTES_PER_SECOND / 1000;
        return new TrackSession() {
            @Override
            public void append(byte[] pcm) {
                if (bytes.addAndGet(pcm.length) > budget) throw MeetingException.tooLong();
                stream.append(pcm);
            }

            @Override
            public CompletableFuture<Void> finish() {
                return stream.finish();
            }

            @Override
            public void close() {
                stream.close();
            }
        };
    }

    private static void requireRecordable(MeetingRepository.Row meeting, Meeting.Track track) {
        if (meeting.status() != Meeting.Status.RECORDING) throw MeetingException.ended();
        if (meeting.kind() == Meeting.Kind.IN_PERSON && track == Meeting.Track.TAB)
            throw MeetingException.invalid("An in-person meeting records the microphone only.");
    }

    /** Refuses anyone but the owner, and answers the meeting as they read it. */
    @Transactional(readOnly = true)
    public Meeting.Detail owned(ActorId actor, UUID id) {
        return detail(tenant(actor), actor, id);
    }

    /** The meeting as its owner reads it. Commands use this after they have locked the row. */
    private Meeting.Detail detail(UUID tenant, ActorId actor, UUID id) {
        return present(tenant, actor.value(), id,
                meetings.find(tenant, actor.value(), id).orElseThrow(MeetingException::notFound));
    }

    /**
     * The meeting as anyone it reaches reads it: its owner, or somebody the owner shared it with. A reader gets the
     * transcript, the speakers and the minutes; the owner's private notes and the list of readers stay with the owner.
     */
    private Meeting.Detail readable(UUID tenant, ActorId actor, UUID id) {
        return present(tenant, actor.value(), id,
                meetings.read(tenant, actor.value(), id).orElseThrow(MeetingException::notFound));
    }

    /** Stars a line for the caller alone, or takes the star off again. */
    @Transactional
    public Meeting.Detail star(ActorId actor, UUID id, UUID utteranceId, boolean starred) {
        UUID tenant = tenant(actor);
        meetings.read(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        if (!meetings.hasUtterance(tenant, id, utteranceId)) throw MeetingException.notFound();
        if (starred) meetings.star(tenant, id, utteranceId, actor.value());
        else meetings.unstar(tenant, utteranceId, actor.value());
        return readable(tenant, actor, id);
    }

    /**
     * Marks the moment the caller is at. It happens while the meeting is still running, so there is no line to
     * attach it to; the label is the caller's, or the next number when they do not give one.
     */
    @Transactional
    public Meeting.Detail bookmark(ActorId actor, UUID id, long atMs, @Nullable String label) {
        UUID tenant = tenant(actor);
        meetings.read(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        if (atMs < 0 || atMs > MAX_TRACK.toMillis()) throw MeetingException.invalid("A bookmark sits inside the recording.");
        var mine = meetings.bookmarks(tenant, id, actor.value());
        if (mine.size() >= MAX_BOOKMARKS) throw MeetingException.invalid("This meeting has enough bookmarks.");
        String clean = label == null || label.isBlank() ? "" : label.strip();
        if (clean.length() > MAX_NAME || clean.chars().anyMatch(Character::isISOControl))
            throw MeetingException.invalid("A bookmark label has at most 200 characters.");
        if (clean.isEmpty()) clean = "Đánh dấu " + (mine.size() + 1);
        meetings.addBookmark(tenant, id, actor.value(), new Meeting.Bookmark(UUID.randomUUID(), atMs, clean));
        return readable(tenant, actor, id);
    }

    @Transactional
    public Meeting.Detail removeBookmark(ActorId actor, UUID id, UUID bookmarkId) {
        UUID tenant = tenant(actor);
        meetings.read(tenant, actor.value(), id).orElseThrow(MeetingException::notFound);
        if (!meetings.deleteBookmark(tenant, id, actor.value(), bookmarkId)) throw MeetingException.notFound();
        return readable(tenant, actor, id);
    }

    /**
     * The speakers, and for the owner the name each unanswered voice gave itself. Only the owner can act on an offer,
     * so only the owner is shown one.
     */
    private List<Meeting.Speaker> named(UUID tenant, UUID id, MeetingRepository.Row row,
            List<Meeting.Utterance> utterances) {
        var speakers = meetings.speakers(tenant, id);
        if (!row.owned()) return speakers;
        var asking = meetings.speakersAskingForAName(tenant, id);
        if (asking.isEmpty()) return speakers;
        var offered = SpeakerIntroductions.suggest(utterances, row.participants(), asking);
        return speakers.stream().map(speaker -> {
            var suggestion = offered.get(SpeakerIntroductions.key(speaker.track(), speaker.label()));
            return suggestion == null ? speaker
                    : new Meeting.Speaker(speaker.track(), speaker.label(), speaker.name(), suggestion);
        }).toList();
    }

    private Meeting.Detail present(UUID tenant, UUID actor, UUID id, MeetingRepository.Row row) {
        var items = row.minutesStatus() == Meeting.MinutesStatus.READY ? meetings.minutesItems(tenant, id) : List.<Meeting.MinutesItem>of();
        var minutes = new Meeting.Minutes(row.minutesStatus(), row.minutesFailure(), row.minutesSummary(), row.minutesKind(),
                row.minutesGeneratedAt(),
                items.stream().filter(item -> item.kind() == Meeting.ItemKind.DECISION).toList(),
                items.stream().filter(item -> item.kind() == Meeting.ItemKind.ACTION).toList(), row.minutesEdited(),
                items.stream().filter(item -> item.kind() == Meeting.ItemKind.TOPIC).toList());
        var utterances = meetings.utterances(tenant, id);
        return new Meeting.Detail(row.id(), row.title(), row.kind(), row.language(), row.participants(), row.terms(),
                row.owned() ? row.notes() : "", row.status(), row.provider(), row.diarized(), row.createdAt(),
                row.endedAt(), row.revision(), named(tenant, id, row, utterances), utterances, minutes,
                new Meeting.Audio(row.audioStatus(), row.audioFailure(), row.audioFilename(), row.audioSizeBytes(),
                        row.audioProvider()),
                row.owned(), row.owned() ? meetings.readers(tenant, id) : List.of(),
                meetings.starred(tenant, id, actor), meetings.bookmarks(tenant, id, actor), row.correcting());
    }

    /** The Tenant the actor is writing in; correction runs need it to bill the model call. */
    public UUID tenantOf(ActorId actor) {
        return tenant(actor);
    }

    private UUID tenant(ActorId actor) {
        return authorization.require(actor, IamCapability.CHAT_WRITE, false).tenantId().value();
    }

    static Meeting.Draft validate(Meeting.Draft draft) {
        if (draft == null || draft.kind() == null) throw MeetingException.invalid("A meeting needs a title and a kind.");
        String title = draft.title() == null ? "" : draft.title().strip();
        if (title.isEmpty() || title.length() > MAX_NAME || title.chars().anyMatch(Character::isISOControl))
            throw MeetingException.invalid("A meeting title has 1 to 200 characters.");
        if (draft.language() != null && !LANGUAGES.contains(draft.language()))
            throw MeetingException.invalid("Unsupported meeting language.");
        return new Meeting.Draft(title, draft.kind(), draft.language(),
                names(draft.participants(), MAX_PARTICIPANTS, MAX_NAME, "participants"),
                names(draft.terms(), MAX_TERMS, MAX_TERM, "terms"));
    }

    /** Trimmed, distinct, non-empty entries within bounds. */
    private static List<String> names(@Nullable List<String> values, int maxCount, int maxLength, String field) {
        if (values == null) return List.of();
        var clean = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null) continue;
            String entry = value.strip();
            if (entry.isEmpty()) continue;
            if (entry.length() > maxLength || entry.chars().anyMatch(Character::isISOControl))
                throw MeetingException.invalid("Invalid meeting " + field + ".");
            clean.add(entry);
        }
        if (clean.size() > maxCount) throw MeetingException.invalid("Too many meeting " + field + ".");
        return new ArrayList<>(clean);
    }

    /** Provider labels are short tokens; anything else is folded into one safe label. */
    private static String speaker(String label) {
        return label.matches("[0-9A-Za-z_-]{1,16}") ? label : "1";
    }

    private static List<Meeting.Span> spans(LiveTranscription.Segment segment) {
        return segment.spans().stream()
                .map(span -> new Meeting.Span(span.start(), span.end(), Math.clamp(span.confidence(), 0, 1))).toList();
    }

    private static String trim(String text) {
        String clean = text.strip();
        return clean.length() > 20_000 ? clean.substring(0, 20_000) : clean;
    }
}
