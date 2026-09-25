package io.memoryos.meeting;

import io.memoryos.BusinessException;
import io.memoryos.ai.TranscriptSummarizer;
import io.memoryos.ai.TranscriptSummary;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.LeasedJob;
import io.memoryos.meeting.persistence.MeetingRepository;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the minutes of meetings that have ended. It runs where the chat model catalog lives, on a fixed delay, and
 * leases one meeting at a time so several API replicas never summarize the same meeting. The model call happens
 * outside any transaction; only the claim and the result are transactional.
 */
@Service
public class MeetingMinutesService {
    private static final Logger LOG = LoggerFactory.getLogger(MeetingMinutesService.class);
    /** A meeting that keeps failing stops being retried, as the usage report does. */
    static final int MAX_ATTEMPTS = 3;
    /** Longer than the model call, so a replica that dies mid-run does not block the meeting for long. */
    static final Duration LEASE = Duration.ofMinutes(10);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final MeetingRepository meetings;
    private final TranscriptSummarizer summarizer;
    private final TransactionTemplate tx;

    public MeetingMinutesService(MeetingRepository meetings, TranscriptSummarizer summarizer,
                                 PlatformTransactionManager transactions) {
        this.meetings = meetings;
        this.summarizer = summarizer;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Writes the minutes of the oldest meeting waiting for them. Returns whether one was claimed. */
    public boolean writeNext() {
        return LeasedJob.runNext(LOG, "meeting.minutes", new LeasedJob.Steps<>(
                () -> Objects.requireNonNull(tx.execute(ignored -> meetings.failAbandonedMinutes(MAX_ATTEMPTS))),
                () -> Objects.requireNonNull(tx.execute(ignored -> meetings.claimMinutes(LEASE, MAX_ATTEMPTS))),
                this::write,
                (claim, failure) -> tx.executeWithoutResult(ignored -> meetings.failMinutes(claim.tenant(), claim.id(),
                        claim.attempts(), MAX_ATTEMPTS, reason(failure)))));
    }

    private void write(MeetingRepository.MinutesClaim claim) {
        var meeting = meetings.find(claim.tenant(), claim.owner(), claim.id()).orElse(null);
        // The owner deleted the meeting while it waited; there is nothing to write.
        if (meeting == null) return;
        var utterances = meetings.utterances(claim.tenant(), claim.id());
        if (utterances.isEmpty()) throw new IllegalStateException("MEETING_EMPTY");
        // The names the owner sees in the transcript, so the minutes and the transcript agree.
        var names = SpeakerNames.of(meetings.speakers(claim.tenant(), claim.id()), meeting.kind(), meeting.language());
        var lines = new ArrayList<TranscriptSummarizer.Line>(utterances.size());
        for (int i = 0; i < utterances.size(); i++) {
            var utterance = utterances.get(i);
            lines.add(new TranscriptSummarizer.Line(i + 1, names.of(utterance), SpeakerNames.clock(utterance.startMs()),
                    utterance.text()));
        }
        var subject = new TranscriptSummarizer.Subject(meeting.title(), meeting.participants(),
                WHEN.format(meeting.createdAt()), meeting.notes(), meeting.language());
        var summary = summarizer.summarize(new ActorId(claim.owner()), claim.tenant(), subject, lines);
        var items = items(summary, utterances);
        boolean stored = Boolean.TRUE.equals(tx.execute(ignored -> meetings.writeMinutes(claim.tenant(), claim.id(),
                claim.attempts(), summary.summary(), summary.kind(), items)));
        // Another replica took the meeting over after this lease lapsed; it owns the outcome.
        if (!stored) LOG.atWarn().addKeyValue("event", "meeting.minutes.lease_lost").log("Meeting minutes lease lapsed before they were stored");
    }

    /** Turns the model's line numbers back into utterance ids, so every item can be traced to what was said. */
    private static List<Meeting.MinutesItem> items(TranscriptSummary summary, List<Meeting.Utterance> utterances) {
        var items = new ArrayList<Meeting.MinutesItem>(summary.decisions().size() + summary.actions().size());
        for (var decision : summary.decisions())
            items.add(new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.DECISION, bounded(decision.text()), null,
                    null, bounded(decision.quote()), source(decision.line(), utterances), false));
        for (var action : summary.actions())
            items.add(new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.ACTION, bounded(action.text()),
                    shortText(action.owner()), shortText(action.due()), bounded(action.quote()),
                    source(action.line(), utterances), false));
        for (var topic : summary.topics())
            items.add(new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.TOPIC, bounded(topic.title()), null,
                    null, null, source(topic.line(), utterances), false));
        return items;
    }

    private static @Nullable UUID source(int line, List<Meeting.Utterance> utterances) {
        return line >= 1 && line <= utterances.size() ? utterances.get(line - 1).id() : null;
    }

    private static String bounded(@Nullable String value) {
        if (value == null) return "";
        String text = value.strip();
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    private static @Nullable String shortText(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        return text.length() > 100 ? text.substring(0, 100) : text;
    }

    /** A safe code for the owner; provider text never reaches the meeting row. */
    private static String reason(RuntimeException failure) {
        if (failure instanceof BusinessException business) return business.code();
        return "MEETING_MINUTES_FAILED";
    }
}
