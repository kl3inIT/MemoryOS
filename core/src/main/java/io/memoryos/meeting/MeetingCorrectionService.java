package io.memoryos.meeting;

import io.memoryos.chat.summary.TranscriptCorrector;
import io.memoryos.shared.ActorId;
import io.memoryos.meeting.persistence.MeetingRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proposes fixes for the stretches a speech provider was unsure of, and applies the ones the owner accepts.
 *
 * <p>Only the owner runs this. A shared meeting can be read by others, but its transcript is the owner's record and
 * the model call is billed to the owner's Tenant.
 *
 * <p>Nothing is ever overwritten in place: {@code meeting_utterance.text} is what the reader sees, and every change
 * to it is an event, so the words the provider first wrote stay recoverable however many passes are run.
 */
@Service
public class MeetingCorrectionService {
    /**
     * How long one pass may hold the meeting before another press may start again. It has to outlast the worst run
     * the corrector allows itself — {@code MAX_STRETCHES / BATCH} calls of {@code TIMEOUT} each — or two passes
     * could write proposals for the same meeting at once.
     */
    static final Duration WINDOW = Duration.ofMinutes(30);
    /** A line nobody could read is not worth a model call. */
    private static final int MIN_STRETCH = 2;

    private final MeetingRepository meetings;
    private final MeetingService details;
    private final TranscriptCorrector corrector;
    private final TransactionTemplate tx;

    public MeetingCorrectionService(MeetingRepository meetings, MeetingService details, TranscriptCorrector corrector,
                                    PlatformTransactionManager transactions) {
        this.meetings = meetings;
        this.details = details;
        this.corrector = corrector;
        this.tx = new TransactionTemplate(transactions);
    }

    /** One pass and what it proposed. */
    public record Run(UUID id, List<Meeting.Correction> corrections) {}

    /**
     * What deciding one stretch changed: the line as a reader now sees it and the proposal as it now stands. One line
     * is all a single decision touches, so the transcript is not read back to answer it.
     */
    public record Applied(Meeting.Utterance utterance, Meeting.Correction correction) {}

    /**
     * Reads every stretch the provider was unsure of and asks the model about each. The transcript is untouched: the
     * answer is a list of offers. Lines the owner has already rewritten are left out — their words are the owner's
     * now, not the provider's.
     */
    public Run propose(ActorId actor, UUID meetingId) {
        UUID tenant = details.tenantOf(actor);
        tx.executeWithoutResult(ignored -> {
            meetings.lock(tenant, actor.value(), meetingId).orElseThrow(MeetingException::notFound);
            if (!meetings.beginCorrection(tenant, actor.value(), meetingId, WINDOW)) throw MeetingException.conflict();
        });
        var meeting = details.get(actor, meetingId);
        UUID run = UUID.randomUUID();
        try {
            var names = new HashMap<String, String>();
            for (var speaker : meeting.speakers())
                if (speaker.name() != null) names.put(speaker.track().name() + speaker.label(), speaker.name());
            var lines = new ArrayList<TranscriptCorrector.Line>(meeting.utterances().size());
            var stretches = new ArrayList<TranscriptCorrector.Stretch>();
            for (var utterance : meeting.utterances()) {
                String speaker = names.getOrDefault(utterance.track().name() + utterance.speaker(), utterance.speaker());
                lines.add(new TranscriptCorrector.Line(utterance.id().toString(), speaker, utterance.text()));
                if (utterance.editSource() == Meeting.EditSource.HUMAN) continue;
                for (var range : merged(utterance)) {
                    if (range.end() - range.start() < MIN_STRETCH) continue;
                    stretches.add(new TranscriptCorrector.Stretch(utterance.id() + ":" + range.start(),
                            utterance.id().toString(), range.start(), range.end()));
                }
            }
            if (stretches.isEmpty()) return new Run(run, List.of());
            // The model call is bounded but slow; no transaction is held open across it.
            var proposed = corrector.propose(actor, tenant,
                    new TranscriptCorrector.Subject(meeting.title(), meeting.terms(), meeting.language()), lines,
                    stretches);
            var byStretch = new HashMap<String, TranscriptCorrector.Stretch>();
            for (var stretch : stretches) byStretch.put(stretch.id(), stretch);
            var text = new HashMap<UUID, String>();
            for (var utterance : meeting.utterances()) text.put(utterance.id(), utterance.text());
            var corrections = new ArrayList<Meeting.Correction>(proposed.proposals().size());
            for (var proposal : proposed.proposals()) {
                var stretch = byStretch.get(proposal.id());
                if (stretch == null) continue;
                UUID utterance = UUID.fromString(stretch.lineId());
                String before = text.getOrDefault(utterance, "")
                        .substring(stretch.start(), Math.min(stretch.end(), text.getOrDefault(utterance, "").length()));
                corrections.add(new Meeting.Correction(UUID.randomUUID(), utterance, run, stretch.start(),
                        stretch.end(), before, proposal.text(), proposal.reason(), proposal.confidence(),
                        proposal.contextFit(), proposal.meaningSafe(), proposal.matchedGlossary(),
                        Meeting.CorrectionStatus.PENDING));
            }
            tx.executeWithoutResult(ignored -> meetings.insertCorrections(tenant, meetingId, run, corrections));
            return new Run(run, List.copyOf(corrections));
        } finally {
            tx.executeWithoutResult(ignored -> meetings.endCorrection(tenant, meetingId));
        }
    }

    /** Every proposal ever made for this meeting, decided or not. A reader sees none of them. */
    public List<Meeting.Correction> corrections(ActorId actor, UUID meetingId) {
        UUID tenant = details.tenantOf(actor);
        return tx.execute(ignored -> {
            meetings.lock(tenant, actor.value(), meetingId).orElseThrow(MeetingException::notFound);
            return meetings.corrections(tenant, meetingId);
        });
    }

    /**
     * Applies one proposal. Passing {@code text} accepts the owner's own wording instead of the model's, which is
     * recorded as the owner's change and locks the line against later passes.
     */
    public Applied accept(ActorId actor, UUID meetingId, UUID correctionId, @Nullable String text) {
        UUID tenant = details.tenantOf(actor);
        return tx.execute(ignored -> {
            meetings.lock(tenant, actor.value(), meetingId).orElseThrow(MeetingException::notFound);
            var correction = meetings.lockCorrection(tenant, meetingId, correctionId)
                    .orElseThrow(MeetingException::notFound);
            return apply(tenant, meetingId, actor, correction, text);
        });
    }

    /**
     * The owner types what was said at one stretch the provider was unsure of. It is recorded like an accepted
     * proposal of their own, so it sits with the other corrections and is taken back the same way, and it locks the
     * line against later passes. Only a marked stretch can be corrected this way, and never while recording.
     */
    public Applied correctByHand(ActorId actor, UUID meetingId, UUID utteranceId, int start, int end, String text) {
        UUID tenant = details.tenantOf(actor);
        return tx.execute(ignored -> {
            var meeting = meetings.lock(tenant, actor.value(), meetingId).orElseThrow(MeetingException::notFound);
            if (meeting.status() != Meeting.Status.ENDED) throw MeetingException.conflict();
            var utterance = meetings.lockUtterance(tenant, meetingId, utteranceId)
                    .orElseThrow(MeetingException::notFound);
            // The page shows marks read as whole words; anything else is stale or was never marked.
            if (utterance.spans().stream().noneMatch(span -> span.start() == start && span.end() == end))
                throw MeetingException.conflict();
            var mark = utterance.spans().stream().filter(span -> span.start() == start).findFirst().orElseThrow();
            var correction = new Meeting.Correction(UUID.randomUUID(), utteranceId, UUID.randomUUID(), start, end,
                    utterance.text().substring(start, end), utterance.text().substring(start, end), "",
                    mark.confidence(), 1, 1, false, Meeting.CorrectionStatus.PENDING);
            meetings.insertCorrections(tenant, meetingId, correction.runId(), List.of(correction));
            return apply(tenant, meetingId, actor, correction, text);
        });
    }

    /**
     * Keeps the provider's words. The proposal stays on the record as offered and declined, and is all this answers:
     * no line changed.
     */
    public Meeting.Correction keep(ActorId actor, UUID meetingId, UUID correctionId) {
        UUID tenant = details.tenantOf(actor);
        return tx.execute(ignored -> {
            meetings.lock(tenant, actor.value(), meetingId).orElseThrow(MeetingException::notFound);
            var correction = meetings.lockCorrection(tenant, meetingId, correctionId)
                    .orElseThrow(MeetingException::notFound);
            if (correction.status() != Meeting.CorrectionStatus.PENDING) throw MeetingException.conflict();
            return meetings.decide(tenant, correctionId, Meeting.CorrectionStatus.KEPT, actor.value());
        });
    }

    /**
     * Applies everything still undecided in one pass. Within a line the later stretches are applied first, so the
     * offsets of the earlier ones are still the offsets they were proposed against.
     */
    public Meeting.Detail acceptAll(ActorId actor, UUID meetingId, UUID runId) {
        UUID tenant = details.tenantOf(actor);
        tx.executeWithoutResult(ignored -> {
            meetings.lock(tenant, actor.value(), meetingId).orElseThrow(MeetingException::notFound);
            var pending = new ArrayList<>(meetings.pendingOfRun(tenant, meetingId, runId));
            pending.sort((left, right) -> left.utteranceId().equals(right.utteranceId())
                    ? Integer.compare(right.start(), left.start())
                    : left.utteranceId().compareTo(right.utteranceId()));
            for (var correction : pending) apply(tenant, meetingId, actor, correction, null);
        });
        return details.get(actor, meetingId);
    }

    /**
     * Takes back everything one pass put in. Accepting in bulk is only safe if undoing in bulk is too: a pass the
     * owner regrets should cost one press, not one press per line.
     */
    public Meeting.Detail revertAll(ActorId actor, UUID meetingId, UUID runId) {
        UUID tenant = details.tenantOf(actor);
        tx.executeWithoutResult(ignored -> {
            meetings.lock(tenant, actor.value(), meetingId).orElseThrow(MeetingException::notFound);
            // Newest first, so a line changed twice is unwound in the order it was written.
            for (var correction : meetings.acceptedOfRun(tenant, meetingId, runId))
                restore(tenant, meetingId, actor, correction);
        });
        return details.get(actor, meetingId);
    }

    /** Puts back what the line said before this proposal was applied. */
    public Applied revert(ActorId actor, UUID meetingId, UUID correctionId) {
        UUID tenant = details.tenantOf(actor);
        return tx.execute(ignored -> {
            meetings.lock(tenant, actor.value(), meetingId).orElseThrow(MeetingException::notFound);
            var correction = meetings.lockCorrection(tenant, meetingId, correctionId)
                    .orElseThrow(MeetingException::notFound);
            return restore(tenant, meetingId, actor, correction);
        });
    }

    /** Caller holds the meeting lock. */
    private Applied restore(UUID tenant, UUID meetingId, ActorId actor, Meeting.Correction correction) {
        if (correction.status() != Meeting.CorrectionStatus.ACCEPTED) throw MeetingException.conflict();
        var utterance = meetings.lockUtterance(tenant, meetingId, correction.utteranceId())
                .orElseThrow(MeetingException::notFound);
        // Only the words this proposal put in can be taken back; anything written over them since has its own
        // history, and putting this line back would quietly discard it.
        int end = correction.start() + correction.after().length();
        if (end > utterance.text().length()
                || !utterance.text().substring(correction.start(), end).equals(correction.after()))
            throw MeetingException.conflict();
        String applied = replaced(utterance.text(), correction.start(), end, correction.before());
        var line = meetings.rewrite(tenant, meetingId, utterance.id(), utterance.text(), applied,
                restored(utterance.spans(), correction), reverted(tenant, utterance.id(), applied),
                correction.runId(), actor.value(), "REVERT");
        return new Applied(line,
                meetings.decide(tenant, correction.id(), Meeting.CorrectionStatus.REVERTED, actor.value()));
    }

    /** Caller holds the meeting lock. */
    private Applied apply(UUID tenant, UUID meetingId, ActorId actor, Meeting.Correction correction, @Nullable String own) {
        if (correction.status() != Meeting.CorrectionStatus.PENDING) throw MeetingException.conflict();
        var utterance = meetings.lockUtterance(tenant, meetingId, correction.utteranceId())
                .orElseThrow(MeetingException::notFound);
        int start = correction.start();
        int end = Math.min(correction.end(), utterance.text().length());
        // The line moved since the pass read it, so these offsets no longer point at the words that were judged.
        if (start > end || !utterance.text().substring(start, end).equals(correction.before()))
            throw MeetingException.conflict();
        String chosen = own == null ? correction.after() : own.strip();
        if (chosen.isEmpty() || chosen.length() > 2000 || chosen.chars().anyMatch(Character::isISOControl))
            throw MeetingException.invalid("A correction has 1 to 2000 characters.");
        // A proposal made before marks were read as whole words can point at half of one, and its answer is the
        // whole word: replacing only the half would leave the rest of the word behind it twice.
        start = Meeting.Span.wordStart(utterance.text(), start);
        end = Meeting.Span.wordEnd(utterance.text(), end);
        String before = utterance.text().substring(start, end);
        String after = replaced(utterance.text(), start, end, chosen);
        var line = meetings.rewrite(tenant, meetingId, utterance.id(), utterance.text(), after,
                shifted(utterance.spans(), start, end, chosen.length()),
                own == null ? Meeting.EditSource.MODEL : Meeting.EditSource.HUMAN, correction.runId(), actor.value(),
                own == null ? "MODEL" : "HUMAN");
        return new Applied(line, meetings.accepted(tenant, correction.id(), actor.value(), start, end, before, chosen));
    }

    /**
     * Who a reverted line belongs to now. Back at the provider's own words means nobody has changed it after all,
     * which also unlocks it for a later pass; otherwise it keeps whoever made the change still standing.
     */
    private Meeting.@Nullable EditSource reverted(UUID tenant, UUID utterance, String text) {
        return meetings.standingEdit(tenant, utterance, text);
    }

    private static List<TranscriptCorrector.Range> merged(Meeting.Utterance utterance) {
        var marks = new ArrayList<TranscriptCorrector.Range>(utterance.spans().size());
        for (var span : utterance.spans()) marks.add(new TranscriptCorrector.Range(span.start(), span.end()));
        return TranscriptCorrector.merge(utterance.text(), marks);
    }

    private static String replaced(String text, int start, int end, String with) {
        return text.substring(0, start) + with + text.substring(Math.min(end, text.length()));
    }

    /**
     * Keeps the remaining marks pointing at the right characters. A replacement of a different length moves
     * everything after it, and a mark that covered the replaced words no longer describes anything.
     */
    static List<Meeting.Span> shifted(List<Meeting.Span> spans, int start, int end, int length) {
        int delta = length - (end - start);
        var kept = new ArrayList<Meeting.Span>(spans.size());
        for (var span : spans) {
            if (span.start() < end && span.end() > start) continue;
            if (span.start() >= end) {
                kept.add(new Meeting.Span(span.start() + delta, span.end() + delta, span.confidence()));
                continue;
            }
            kept.add(span);
        }
        return List.copyOf(kept);
    }

    /** A reverted line gets its marks back for the stretch that was put back, and keeps the rest in place. */
    private static List<Meeting.Span> restored(List<Meeting.Span> spans, Meeting.Correction correction) {
        int delta = correction.before().length() - correction.after().length();
        var kept = new ArrayList<Meeting.Span>(spans.size() + 1);
        for (var span : spans) {
            if (span.start() >= correction.start() + correction.after().length())
                kept.add(new Meeting.Span(span.start() + delta, span.end() + delta, span.confidence()));
            else kept.add(span);
        }
        kept.add(new Meeting.Span(correction.start(), correction.start() + correction.before().length(),
                correction.confidence()));
        kept.sort(java.util.Comparator.comparingInt(Meeting.Span::start));
        return List.copyOf(kept);
    }
}
