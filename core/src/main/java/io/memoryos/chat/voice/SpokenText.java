package io.memoryos.chat.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles one utterance from a provider's tokens, keeping both the mean confidence and the stretches the provider
 * was least sure of. Offsets are into {@link #said()}, the finished text, so whatever stores that text can mark
 * exactly those characters without knowing anything about tokens.
 */
final class SpokenText {
    /**
     * Below this a token is worth showing as uncertain. It is Soniox's own review threshold, and it is applied once,
     * here, when the utterance is built — stored marks reflect the threshold in force at transcription time.
     */
    static final double UNCERTAIN = 0.6;

    private final StringBuilder text = new StringBuilder();
    private final List<int[]> marks = new ArrayList<>();
    private final List<Double> markConfidence = new ArrayList<>();
    private double total;
    private int tokens;

    /** Appends one token exactly as the provider wrote it, including any leading space. */
    void append(String word, double confidence) {
        int from = text.length();
        text.append(word);
        total += confidence;
        tokens++;
        if (confidence >= UNCERTAIN) return;
        // A Soniox token carries its leading space (" Khê"); a mark that started there would begin on whitespace.
        while (from < text.length() && Character.isWhitespace(text.charAt(from))) from++;
        int to = text.length();
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) to--;
        if (from >= to) return;
        int last = marks.size() - 1;
        if (last >= 0 && blankBetween(marks.get(last)[1], from)) {
            // Neighbouring uncertain tokens read as one uncertain stretch; its confidence is the worst of them.
            marks.get(last)[1] = to;
            markConfidence.set(last, Math.min(markConfidence.get(last), confidence));
            return;
        }
        marks.add(new int[] {from, to});
        markConfidence.add(confidence);
    }

    int length() {
        return text.length();
    }

    boolean isEmpty() {
        return text.toString().isBlank();
    }

    /** The utterance as it will be stored. */
    String said() {
        return text.toString().strip();
    }

    /** The mean over every token, whatever its confidence; zero when nothing was appended. */
    double confidence() {
        return tokens == 0 ? 0 : total / tokens;
    }

    /** The uncertain stretches, in the coordinates of {@link #said()}. */
    List<LiveTranscription.Span> spans() {
        int shift = text.length() - text.toString().stripLeading().length();
        int limit = said().length();
        var spans = new ArrayList<LiveTranscription.Span>(marks.size());
        for (int i = 0; i < marks.size(); i++) {
            int start = Math.clamp(marks.get(i)[0] - shift, 0, limit);
            int end = Math.clamp(marks.get(i)[1] - shift, 0, limit);
            if (start < end) spans.add(new LiveTranscription.Span(start, end, markConfidence.get(i)));
        }
        return List.copyOf(spans);
    }

    void reset() {
        text.setLength(0);
        marks.clear();
        markConfidence.clear();
        total = 0;
        tokens = 0;
    }

    private boolean blankBetween(int from, int to) {
        if (from > to) return false;
        for (int i = from; i < to; i++) if (!Character.isWhitespace(text.charAt(i))) return false;
        return true;
    }
}
