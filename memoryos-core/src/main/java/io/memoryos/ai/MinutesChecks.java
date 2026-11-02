package io.memoryos.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What the minutes must hold before they are stored, whatever the model wrote. The prompt already asks for one owner,
 * exact quotes and no repeats; on 27 staging meetings the model broke each of those often enough (61 of 119 owners a
 * speaker label or a group, 27 of 175 quotes off their line, 16 items on a line another item cites) that the rules are
 * enforced here instead of hoped for.
 */
final class MinutesChecks {
    /** How the transcript names a voice nobody named: {@code SpeakerNames} in the meeting module. */
    private static final Pattern LABEL = Pattern.compile("(người nói|speaker) \\S{1,16}");
    /** Words that make an owner a group rather than a person, in the languages the minutes are written in. */
    private static final Pattern GROUP = Pattern.compile("(?<![\\p{L}\\p{N}])(" + String.join("|", List.of(
            "nhóm", "team", "đội", "phòng", "ban", "bộ phận", "các bạn", "các em", "các cháu", "các anh", "các chị",
            "bọn em", "bọn anh", "bọn mình", "chúng em", "chúng tôi", "chúng ta", "anh em", "mọi người", "cả hai",
            "tất cả", "đơn vị", "nhà thầu", "công ty", "everyone", "both", "we")) + ")(?![\\p{L}\\p{N}])");
    /** Two or more people in one owner. */
    private static final Pattern SEVERAL = Pattern.compile("(?<![\\p{L}\\p{N}])(và|với|and)(?![\\p{L}\\p{N}])|[,/&]");
    private static final Pattern PUNCTUATION = Pattern.compile("\\p{P}+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}\\p{M}]+");
    private static final int MIN_ABRIDGED_WORDS = 3;
    private static final Pattern SPACE = Pattern.compile("\\s+");
    /** Where a quote is looked for around its cited line, the nearest first, up to two lines either side. */
    private static final int[] NEAREST_FIRST = {0, -1, 1, -2, 2};
    /** Items of one kind on one line are one item when their words overlap this much. */
    static final double SAME_LINE_OVERLAP = 0.6;
    /** Items of one kind anywhere are one item when their words overlap this much. */
    static final double SAME_WORDS_OVERLAP = 0.8;

    private MinutesChecks() {}

    /** The decisions and actions after the owner, quote and repeat checks, in the order the model wrote them. */
    record Items(List<TranscriptSummary.Decision> decisions, List<TranscriptSummary.Action> actions) {}

    static Items check(List<TranscriptSummary.Decision> decisions, List<TranscriptSummary.Action> actions,
                       List<TranscriptSummarizer.Line> lines) {
        var originals = lines.stream().map(TranscriptSummarizer.Line::text).toList();
        var texts = originals.stream().map(MinutesChecks::bare).toList();
        var cited = new ArrayList<TranscriptSummary.Decision>(decisions.size());
        for (var decision : decisions) {
            var at = cite(decision.quote(), decision.line(), originals, texts);
            cited.add(new TranscriptSummary.Decision(decision.text(), at.quote(), at.line()));
        }
        var actionLines = new HashSet<Integer>();
        var keptActions = new ArrayList<TranscriptSummary.Action>(actions.size());
        for (var action : actions) {
            var at = cite(action.quote(), action.line(), originals, texts);
            var checked = new TranscriptSummary.Action(action.text(), owner(action.owner()), action.due(), at.quote(),
                    at.line());
            if (keptActions.stream().noneMatch(kept -> same(kept.text(), kept.line(), checked.text(), checked.line()))) {
                keptActions.add(checked);
                if (checked.line() > 0) actionLines.add(checked.line());
            }
        }
        // An action carries the owner and the deadline; a decision on the same sentence is the action restated.
        var keptDecisions = new ArrayList<TranscriptSummary.Decision>(cited.size());
        for (var decision : cited) {
            if (decision.line() > 0 && actionLines.contains(decision.line())) continue;
            if (keptDecisions.stream().noneMatch(kept -> same(kept.text(), kept.line(), decision.text(), decision.line())))
                keptDecisions.add(decision);
        }
        return new Items(List.copyOf(keptDecisions), List.copyOf(keptActions));
    }

    /** One person, or nobody: a speaker label, a group and several people are not an owner. */
    static @Nullable String owner(@Nullable String owner) {
        if (owner == null || owner.isBlank()) return null;
        String folded = fold(owner);
        if (LABEL.matcher(folded).matches() || GROUP.matcher(folded).find() || SEVERAL.matcher(folded).find()) return null;
        return owner;
    }

    record Cited(@Nullable String quote, int line) {}

    /**
     * Where the quote was said: its own line, a line up to two away, or two or three lines in a row starting there.
     * A quote that is nowhere near is dropped and the line kept; an item with no line takes the one place its quote
     * matches, when there is exactly one.
     */
    static Cited cite(@Nullable String quote, int line, List<String> originals, List<String> texts) {
        if (quote == null) return new Cited(null, line);
        String wanted = bare(quote);
        if (wanted.isEmpty()) return new Cited(null, line);
        int count = texts.size();
        if (line >= 1 && line <= count) {
            if (texts.get(line - 1).contains(wanted)) return new Cited(quote, line);
            for (int offset : NEAREST_FIRST) {
                int other = line + offset;
                if (offset != 0 && other >= 1 && other <= count && texts.get(other - 1).contains(wanted))
                    return new Cited(quote, other);
            }
            for (int offset : NEAREST_FIRST)
                for (int width = 2; width <= 3; width++) {
                    int start = line + offset;
                    if (start >= 1 && start + width - 1 <= count
                            && String.join(" ", texts.subList(start - 1, start - 1 + width)).contains(wanted))
                        return new Cited(quote, start);
                }
            for (int offset : NEAREST_FIRST) {
                int other = line + offset;
                if (other < 1 || other > count) continue;
                String said = abridged(quote, originals.get(other - 1));
                if (said != null) return new Cited(said, other);
            }
            return new Cited(null, line);
        }
        int found = 0;
        for (int other = 1; other <= count; other++) {
            if (!texts.get(other - 1).contains(wanted)) continue;
            if (found > 0) return new Cited(null, line);
            found = other;
        }
        return found > 0 ? new Cited(quote, found) : new Cited(null, line);
    }

    /**
     * The line's own words for a quote the model shortened: when every word of the quote appears in the line in the
     * same order, close together, the stretch of the line from its first to its last word. A quote of fewer than
     * three words is too short to place this way.
     */
    static @Nullable String abridged(String quote, String line) {
        var wanted = tokens(quote);
        if (wanted.size() < MIN_ABRIDGED_WORDS) return null;
        var heard = tokens(line);
        int from = -1, to = -1;
        for (int first = 0; first < heard.size(); first++) {
            if (!heard.get(first).word().equals(wanted.getFirst().word())) continue;
            int at = first, matched = 1;
            for (int next = first + 1; next < heard.size() && matched < wanted.size(); next++)
                if (heard.get(next).word().equals(wanted.get(matched).word())) {
                    matched++;
                    at = next;
                }
            if (matched == wanted.size() && (from < 0 || at - first < to - from)) {
                from = first;
                to = at;
            }
        }
        // Gaps are allowed, but a quote spread thinly over a long line is not what was said there.
        if (from < 0 || to - from + 1 > Math.max(wanted.size() * 2, wanted.size() + 8)) return null;
        return line.substring(heard.get(from).start(), heard.get(to).end());
    }

    private record Token(String word, int start, int end) {}

    private static List<Token> tokens(String text) {
        var found = new ArrayList<Token>();
        var matcher = WORD.matcher(text);
        while (matcher.find())
            found.add(new Token(Normalizer.normalize(matcher.group(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT),
                    matcher.start(), matcher.end()));
        return found;
    }

    private static boolean same(String text, int line, String otherText, int otherLine) {
        double overlap = overlap(text, otherText);
        return overlap >= SAME_WORDS_OVERLAP || (line > 0 && line == otherLine && overlap >= SAME_LINE_OVERLAP);
    }

    /** Shared words over all words of the two texts. */
    static double overlap(String left, String right) {
        Set<String> a = words(left), b = words(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        var union = new HashSet<>(a);
        union.addAll(b);
        return (double) a.stream().filter(b::contains).count() / union.size();
    }

    private static Set<String> words(String text) {
        String bare = bare(text);
        return bare.isEmpty() ? Set.of() : Arrays.stream(bare.split(" ")).collect(Collectors.toSet());
    }

    /** Composed, lower case, one space between words. */
    static String fold(String text) {
        return SPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)).replaceAll(" ")
                .strip();
    }

    /** {@link #fold} without punctuation, so a quote matches its line whatever the model did to commas and dashes. */
    static String bare(String text) {
        return SPACE.matcher(PUNCTUATION.matcher(fold(text)).replaceAll(" ")).replaceAll(" ").strip();
    }
}
