package io.memoryos.shared;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Text on a PDFBox page the way every PDF MemoryOS writes sets it. The built-in PDF typefaces cannot draw Vietnamese,
 * so a bundled TrueType face is embedded; text is composed to NFC so its marks land on the face's precomposed glyphs;
 * and a character the face lacks becomes a question mark instead of failing the whole file. One instance serves one
 * document, because its fonts belong to that document and the glyph answers are remembered per font.
 */
public final class PdfText {
    private final PDDocument document;
    private final Map<PDType0Font, Map<Integer, Boolean>> glyphs = new HashMap<>();

    public PdfText(PDDocument document) {
        this.document = document;
    }

    /** A face bundled under {@code /fonts/}, embedded as a subset of the glyphs the document uses. */
    public PDType0Font font(String file) {
        try (InputStream in = PdfText.class.getResourceAsStream("/fonts/" + file)) {
            if (in == null) throw new IOException("Missing font " + file);
            return PDType0Font.load(document, in, true);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The value as the face can draw it: composed to NFC, with every character it lacks replaced by "?". */
    public String safe(String value, PDType0Font font) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        var known = glyphs.computeIfAbsent(font, ignored -> new HashMap<>());
        var out = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(point -> {
            boolean drawable = known.computeIfAbsent(point, code -> {
                try {
                    font.encode(new String(Character.toChars(code)));
                    return true;
                } catch (IllegalArgumentException | IOException missing) {
                    return false;
                }
            });
            if (drawable) out.appendCodePoint(point);
            else out.append('?');
        });
        return out.toString();
    }

    /** The width in points of text the face can draw, as {@link #safe} returns it. */
    public static float width(String text, PDType0Font font, float size) {
        try {
            return font.getStringWidth(text) / 1000 * size;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Greedy wrapping on spaces into lines of words: the first line is {@code first} wide and the rest {@code rest}.
     * A word longer than a whole line is cut rather than run off the page. Empty text is one empty line. The text must
     * already be {@linkplain #safe safe} for the face.
     */
    public static List<List<String>> words(String text, PDType0Font font, float size, float first, float rest) {
        var lines = new ArrayList<List<String>>();
        var current = new ArrayList<String>();
        float limit = first;
        float used = 0;
        float space = width(" ", font, size);
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            float length = width(word, font, size);
            if (!current.isEmpty() && used + space + length > limit) {
                lines.add(current);
                current = new ArrayList<>();
                used = 0;
                limit = rest;
            }
            while (length > limit && word.length() > 1) {
                if (!current.isEmpty()) {
                    lines.add(current);
                    current = new ArrayList<>();
                    used = 0;
                    limit = rest;
                }
                int cut = cut(word, font, size, limit);
                lines.add(List.of(word.substring(0, cut)));
                word = word.substring(cut);
                length = width(word, font, size);
                limit = rest;
            }
            used += (current.isEmpty() ? 0 : space) + length;
            current.add(word);
        }
        lines.add(current);
        return lines;
    }

    /** {@link #words} joined back into one string per line. */
    public static List<String> lines(String text, PDType0Font font, float size, float first, float rest) {
        return words(text, font, size, first, rest).stream().map(words -> String.join(" ", words)).toList();
    }

    /** How much of the word fits in the line, at least one character and never half of a surrogate pair. */
    private static int cut(String word, PDType0Font font, float size, float limit) {
        int cut = word.length();
        while (cut > 1 && width(word.substring(0, cut), font, size) > limit) cut--;
        if (cut < word.length() && Character.isHighSurrogate(word.charAt(cut - 1)) && cut > 1) cut--;
        return cut;
    }
}
