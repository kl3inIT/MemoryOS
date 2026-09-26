package io.memoryos.chat.grounding;

import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What of an answer the person may see yet (MEM-195). Answer text passes through here before it is stored or streamed.
 *
 * <p>A grounded turn holds everything until the first inline citation that names evidence registered in this turn,
 * then releases the text and streams the rest as it arrives. A citation naming no registered evidence is dropped, as
 * Onyx {@code citation_processor} drops unknown numbers. An answer that ends without a valid citation is never shown.
 *
 * <p>Blocked phrases are checked on every chunk against the text not yet released plus the last released characters,
 * and as many trailing characters as the longest phrase is held back, so a phrase split across two chunks is still
 * caught (the carried-over context of NeMo Guardrails {@code RollingBuffer}). A match stops the answer.
 */
public final class CitationGate {
    /** Inline citations as the browser recognises them ({@code chat-evidence.ts}). */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,5})]");
    private static final int MARKER_TAIL = 7;

    public enum Outcome { ANSWERED, UNCITED, BLOCKED }

    /** The last text to release and how the answer ended. */
    public record Ending(String text, Outcome outcome, @Nullable String phrase) {}

    private final boolean grounded;
    private final IntPredicate registered;
    private final Function<String, @Nullable String> blockedPhrase;
    private final int keepBack;
    private final StringBuilder pending = new StringBuilder();
    private String recent = "";
    private boolean cited;
    private @Nullable String blocked;

    /**
     * @param grounded      hold until a valid citation and drop unknown citations
     * @param registered    whether a citation number names evidence registered in this turn
     * @param blockedPhrase the blocked phrase a text contains, or null
     * @param longestPhrase length of the longest blocked phrase, 0 when there are none
     */
    public CitationGate(boolean grounded, IntPredicate registered, Function<String, @Nullable String> blockedPhrase, int longestPhrase) {
        this.grounded = grounded;
        this.registered = registered;
        this.blockedPhrase = blockedPhrase;
        this.keepBack = Math.max(0, longestPhrase - 1);
    }

    /** Takes the next chunk and returns the text that may be shown now, possibly empty. */
    public synchronized String accept(String chunk) {
        if (blocked != null) return "";
        pending.append(chunk);
        if (grounded) dropUnknownCitations();
        if (blockedPhraseMatches()) return "";
        if (grounded && !cited) return "";
        int hold = Math.max(keepBack, grounded ? partialCitation() : 0);
        return release(pending.length() - hold);
    }

    /** Ends the answer: the text still held, and whether the answer may stand. */
    public synchronized Ending finish() {
        if (blocked != null) return new Ending("", Outcome.BLOCKED, blocked);
        if (grounded) dropUnknownCitations();
        if (blockedPhraseMatches()) return new Ending("", Outcome.BLOCKED, blocked);
        if (grounded && !cited) {
            pending.setLength(0);
            return new Ending("", Outcome.UNCITED, null);
        }
        return new Ending(release(pending.length()), Outcome.ANSWERED, null);
    }

    public synchronized boolean released() { return !recent.isEmpty(); }

    private boolean blockedPhraseMatches() {
        if (blocked != null) return true;
        String hit = blockedPhrase.apply(recent + pending);
        if (hit != null) { blocked = hit; pending.setLength(0); }
        return blocked != null;
    }

    private String release(int length) {
        if (length <= 0) return "";
        String out = pending.substring(0, length);
        pending.delete(0, length);
        String joined = recent + out;
        recent = joined.substring(Math.max(0, joined.length() - Math.max(keepBack, 1)));
        return out;
    }

    private void dropUnknownCitations() {
        var matcher = CITATION.matcher(pending);
        var kept = new StringBuilder(pending.length());
        int last = 0;
        while (matcher.find()) {
            kept.append(pending, last, matcher.start());
            if (registered.test(Integer.parseInt(matcher.group(1)))) {
                kept.append(matcher.group());
                cited = true;
            }
            last = matcher.end();
        }
        if (last == 0) return;
        kept.append(pending, last, pending.length());
        pending.setLength(0);
        pending.append(kept);
    }

    /** Length of a citation still being written at the end, such as {@code "[1"}, which must not be released yet. */
    private int partialCitation() {
        int open = pending.lastIndexOf("[");
        if (open < 0 || pending.length() - open > MARKER_TAIL) return 0;
        for (int i = open + 1; i < pending.length(); i++) if (!Character.isDigit(pending.charAt(i))) return 0;
        return pending.length() - open;
    }
}
