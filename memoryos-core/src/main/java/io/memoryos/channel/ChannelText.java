package io.memoryos.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How an answer is cut for a provider that sends it in messages of a bounded length. */
public final class ChannelText {
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?…。！？](?=\\s)");

    private ChannelText() {
    }

    /**
     * The text in pieces of at most {@code max} characters, each cut at the last paragraph end that fits, else the
     * last line end, else the last sentence end, else the last space, else where the length runs out (not inside a
     * surrogate pair, unless a piece holds a single char). Pieces are trimmed; none is empty.
     */
    public static List<String> pieces(String text, int max) {
        if (max < 1) throw new IllegalArgumentException("max must be positive");
        var pieces = new ArrayList<String>();
        String rest = text.strip();
        while (rest.length() > max) {
            int cut = cut(rest, max);
            String piece = rest.substring(0, cut).strip();
            if (!piece.isEmpty()) pieces.add(piece);
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) pieces.add(rest);
        return List.copyOf(pieces);
    }

    private static int cut(String text, int max) {
        String window = text.substring(0, max + 1);
        int at = window.lastIndexOf("\n\n");
        if (at > 0) return at;
        at = window.lastIndexOf('\n');
        if (at > 0) return at;
        int sentence = -1;
        Matcher matcher = SENTENCE_END.matcher(window);
        while (matcher.find()) sentence = matcher.end();
        if (sentence > 0) return sentence;
        at = window.lastIndexOf(' ');
        if (at > 0) return at;
        return max > 1 && Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
    }
}
