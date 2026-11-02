package io.memoryos.meeting;

import io.memoryos.library.UserFileInUseException;
import io.memoryos.library.UserFileService;
import io.memoryos.library.LibraryFile;
import io.memoryos.library.LibraryService;
import io.memoryos.shared.ActorId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A meeting's minutes in the owner's file library: taken in so a conversation can use them, and dropped when the
 * minutes are written again. The library is Chat's until it becomes its own module (ADR 0015, step 3); meetings
 * reach it only through {@code chat :: library}.
 */
@Service
public class MeetingLibraryService {
    private static final Logger LOG = LoggerFactory.getLogger(MeetingLibraryService.class);

    private final MeetingService meetings;
    private final LibraryService library;
    private final UserFileService files;

    public MeetingLibraryService(MeetingService meetings, LibraryService library, UserFileService files) {
        this.meetings = meetings;
        this.library = library;
        this.files = files;
    }

    /** The minutes as a file in the caller's library. */
    public record PublishedMinutes(UUID fileId, String filename, String status) {}

    /**
     * Takes the minutes into the caller's library as they read now. Asking again returns the file already made while
     * the minutes still read the same; once an edit, a named voice or a new title changed them, that file is replaced,
     * so whoever takes them into a conversation reads the current wording. An earlier file a Project or an Agent
     * still holds is neither pulled from them nor handed out as current: the call is refused and names the holder.
     */
    public PublishedMinutes publish(ActorId actor, UUID meetingId) {
        var meeting = meetings.get(actor, meetingId);
        if (meeting.minutes().status() != Meeting.MinutesStatus.READY)
            throw MeetingException.invalid("The minutes are not written yet.");
        byte[] markdown = MeetingMinutesMarkdown.render(meeting);
        library.outdated(actor, LibraryFile.Source.MEETING, meetingId, markdown)
                .ifPresent(file -> files.delete(actor, file.id()));
        var file = library.publish(actor, LibraryFile.Source.MEETING, meetingId,
                MeetingMinutesMarkdown.filename(meeting), "text/markdown", markdown);
        return new PublishedMinutes(file.id(), file.filename(), file.status().name());
    }

    /**
     * Queues the minutes to be written again. The published minutes are about to be wrong, so they are dropped and
     * the next use publishes what was rewritten; a file a Project or an Agent still holds is left alone rather than
     * pulled out from under them.
     */
    public Meeting.Detail rerunMinutes(ActorId actor, UUID meetingId, boolean discardEdits) {
        var meeting = meetings.rerunMinutes(actor, meetingId, discardEdits);
        library.published(actor, LibraryFile.Source.MEETING, meetingId).ifPresent(file -> {
            try {
                files.delete(actor, file.id());
            } catch (UserFileInUseException inUse) {
                LOG.atInfo().addKeyValue("event", "meeting.minutes.publication_kept")
                        .log("Published meeting minutes kept because something still uses them");
            }
        });
        return meeting;
    }
}
