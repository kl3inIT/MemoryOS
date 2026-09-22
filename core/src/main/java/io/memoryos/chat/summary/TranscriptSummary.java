package io.memoryos.chat.summary;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a model made of one transcript. Every decision and action cites the line it rests on, so a reader can check it;
 * an item without a quote is dropped rather than shown unsupported.
 */
public record TranscriptSummary(String summary, String kind, List<Decision> decisions, List<Action> actions) {
    public TranscriptSummary {
        decisions = List.copyOf(decisions);
        actions = List.copyOf(actions);
    }

    /** A choice the meeting settled on. */
    public record Decision(String text, @Nullable String quote, int line) {}

    /**
     * Work someone took on. An owner is named only where the transcript names one; a task nobody owns is not an
     * action (silent-notetaker), and a vague sentence is not a task at all (ghiam-pro).
     */
    public record Action(String text, @Nullable String owner, @Nullable String due, @Nullable String quote, int line) {}
}
