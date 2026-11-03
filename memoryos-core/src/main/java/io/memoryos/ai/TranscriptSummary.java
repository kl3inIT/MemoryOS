package io.memoryos.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a model made of one transcript. Every decision and action cites the line it rests on, so a reader can check it;
 * an item without a quote is dropped rather than shown unsupported.
 *
 * <p>The first reading's schema is generated from the item records (MEM-305): each field says what it holds, and the
 * property order is the order the model writes in, so an item's quote and line come before its words and owner and
 * the item is written from its evidence. The record as a whole is what the two readings add up to (MEM-306).
 */
@JsonPropertyOrder({"decisions", "actions", "topics", "summary", "kind"})
public record TranscriptSummary(
        @JsonPropertyDescription("3 to 6 sentences on what the meeting settled and what is still open, outcomes first, "
                + "in plain sentences without headings or markup.")
        String summary,
        @JsonPropertyDescription("What kind of meeting this was, in at most 6 words, such as a weekly briefing, a "
                + "project review or an interview.")
        String kind,
        @JsonPropertyDescription("Every choice the meeting settled, each once.")
        List<Decision> decisions,
        @JsonPropertyDescription("Every piece of work someone will do after the meeting, each once.")
        List<Action> actions,
        @JsonPropertyDescription("The subjects the meeting moved through, in order; only real changes of subject.")
        List<Topic> topics) {
    public TranscriptSummary {
        decisions = List.copyOf(decisions);
        actions = List.copyOf(actions);
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    /** Where the meeting moved on to something else: a few words for it, and the line it began on. */
    @JsonPropertyOrder({"line", "title"})
    public record Topic(
            @JsonPropertyDescription("A title of at most 8 words.") String title,
            @JsonPropertyDescription("The number in brackets of the line where the subject began.") int line) {}

    /** A choice the meeting settled on. */
    @JsonPropertyOrder({"quote", "line", "text"})
    public record Decision(
            @JsonPropertyDescription("The decision in one short sentence.") String text,
            @JsonPropertyDescription(QUOTE) @Nullable String quote,
            @JsonPropertyDescription(LINE) int line) {}

    /**
     * Work someone will do. Its owner is the one person named as doing it or who took it on; work that falls to a
     * group or to nobody clear keeps no owner and stays, as Nojoin keeps it unassigned and opennotetaker leaves the
     * owner null.
     */
    @JsonPropertyOrder({"quote", "line", "text", "owner", "due"})
    public record Action(
            @JsonPropertyDescription("The work in one short sentence that names what is done and to what.") String text,
            @JsonPropertyDescription("The one person who will do it, as the transcript names them or as the speaker "
                    + "name shown before the lines of a speaker who took it on; null for a group, several people or "
                    + "nobody clear.") @Nullable String owner,
            @JsonPropertyDescription("The deadline in the words the meeting used, such as thứ Sáu or 30/09; null when "
                    + "none was said.") @Nullable String due,
            @JsonPropertyDescription(QUOTE) @Nullable String quote,
            @JsonPropertyDescription(LINE) int line) {}

    private static final String QUOTE = "The sentence the item rests on, copied exactly from the transcript.";
    private static final String LINE = "The number in brackets at the start of that sentence's line.";
}
