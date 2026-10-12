package io.memoryos.meeting;

import io.memoryos.meeting.persistence.MeetingRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meetings whose transcript the platform they were held on already wrote. One becomes a meeting that has ended, owned
 * by the member who organized it and read by the people who attended; nothing is recorded and no audio is involved.
 * A platform conference is imported once: looked at again, or deleted by its owner, it stays as it is.
 */
@Service
public class MeetingImportService {
    /** A platform admits far more people than a meeting names; past this the list stops meaning who was there. */
    static final int MAX_ATTENDEES = 1_000;
    static final int MAX_EMAIL = 320;
    private static final int MAX_LINE = 20_000;
    private final MeetingRepository meetings;

    public MeetingImportService(MeetingRepository meetings) {
        this.meetings = meetings;
    }

    /** One line as the platform wrote it; {@code voice} is the platform's own name for whoever spoke. */
    public record Line(String voice, @Nullable String name, long startMs, long endMs, String text) {}

    /**
     * A platform's transcript of one conference. {@code externalId} is the platform's identifier of the conference,
     * {@code attendees} the addresses the people in it signed in with.
     */
    public record Transcript(Meeting.Origin origin, String externalId, String title, @Nullable String language,
                             Instant startedAt, Instant endedAt, List<Line> lines, List<String> attendees) {
        public Transcript {
            lines = List.copyOf(lines);
            attendees = List.copyOf(attendees);
        }
    }

    /**
     * Stores the transcript as a meeting of {@code owner} and answers its id, or nothing when this conference was
     * looked at before, nobody spoke in it, or the owner is not an active member of the Tenant.
     */
    @Transactional
    public Optional<UUID> importTranscript(TenantId tenant, ActorId owner, Transcript transcript) {
        if (!transcript.origin().imported()) throw new IllegalArgumentException("origin must be a platform");
        if (transcript.externalId().isBlank()) throw new IllegalArgumentException("externalId must not be blank");
        if (transcript.endedAt().isBefore(transcript.startedAt()))
            throw new IllegalArgumentException("a conference ends after it starts");
        if (meetings.members(tenant.value(), List.of(owner.value())).isEmpty()) return Optional.empty();
        if (!meetings.claimExternal(tenant.value(), transcript.origin(), transcript.externalId()))
            return Optional.empty();

        // The platform's names for its voices become short labels in the order they first speak.
        var labels = new LinkedHashMap<String, Meeting.Speaker>();
        var utterances = new ArrayList<Meeting.Utterance>(transcript.lines().size());
        for (var line : transcript.lines()) {
            String text = line.text().strip();
            if (text.isEmpty()) continue;
            var speaker = labels.computeIfAbsent(line.voice(), ignored -> new Meeting.Speaker(Meeting.Track.IMPORTED,
                    Integer.toString(labels.size() + 1), name(line.name())));
            long start = Math.max(0, line.startMs());
            utterances.add(new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.IMPORTED, speaker.label(), start,
                    Math.max(start, line.endMs()), text.length() > MAX_LINE ? text.substring(0, MAX_LINE) : text, 1));
        }
        // The conference stays claimed: a transcript nobody spoke in is not read again on every run.
        if (utterances.isEmpty()) return Optional.empty();

        var participants = labels.values().stream().map(Meeting.Speaker::name).filter(name -> name != null).distinct()
                .limit(MeetingService.MAX_PARTICIPANTS).toList();
        var draft = MeetingService.validate(new Meeting.Draft(title(transcript.title()), Meeting.Kind.ONLINE,
                language(transcript.language()), participants, List.of()));
        UUID id = UUID.randomUUID();
        meetings.insertImported(tenant.value(), id, owner.value(), transcript.origin(), transcript.externalId(), draft,
                transcript.startedAt(), transcript.endedAt(), List.copyOf(labels.values()), utterances,
                attendees(transcript.attendees()));
        return Optional.of(id);
    }

    /** A platform title is somebody's free text: one line, within the bound, never empty. */
    private static String title(String title) {
        String clean = title.chars().filter(character -> !Character.isISOControl(character))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (clean.isEmpty()) throw new IllegalArgumentException("title must not be blank");
        return clean.length() > MeetingService.MAX_NAME ? clean.substring(0, MeetingService.MAX_NAME).strip() : clean;
    }

    private static @Nullable String name(@Nullable String name) {
        String clean = name == null ? "" : name.strip();
        boolean usable = !clean.isEmpty() && clean.length() <= MeetingService.MAX_NAME
                && clean.chars().noneMatch(Character::isISOControl);
        return usable ? clean : null;
    }

    /** {@code vi-VN} is {@code vi}; a language the minutes are not written in is left to the model. */
    private static @Nullable String language(@Nullable String language) {
        if (language == null) return null;
        String primary = language.strip().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        return primary.equals("vi") || primary.equals("en") ? primary : null;
    }

    /** Lower-case, distinct addresses; anything that is not one grants nobody anything, so it is dropped. */
    private static List<String> attendees(List<String> emails) {
        var clean = new LinkedHashSet<String>();
        for (String email : emails) {
            String address = email.strip().toLowerCase(Locale.ROOT);
            int at = address.indexOf('@');
            boolean usable = at > 0 && at < address.length() - 1 && address.length() <= MAX_EMAIL
                    && address.chars().noneMatch(character -> Character.isWhitespace(character)
                            || Character.isISOControl(character));
            if (usable) clean.add(address);
            if (clean.size() == MAX_ATTENDEES) break;
        }
        return List.copyOf(clean);
    }
}
