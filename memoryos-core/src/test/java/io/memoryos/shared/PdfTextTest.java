package io.memoryos.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/** Text as every MemoryOS PDF sets it: drawable, measured, and wrapped without running off the page. */
class PdfTextTest {
    @Test
    void aWordLongerThanALineIsCutAcrossLinesAndNothingIsLost() throws Exception {
        try (var document = new PDDocument()) {
            var text = new PdfText(document);
            var font = text.font("HankenGrotesk-Regular.ttf");
            String url = "https://example.com/" + "rất-dài-".repeat(40) + "hết";
            String safe = text.safe("Xem " + url + " ngay", font);
            float width = 120;

            var lines = PdfText.lines(safe, font, 10, 60, width);

            assertTrue(lines.size() > 3, "the long word spans several lines");
            assertEquals("Xem", lines.getFirst(), "the word that fits stays on the first, narrower line");
            for (int i = 0; i < lines.size(); i++)
                assertTrue(PdfText.width(lines.get(i), font, 10) <= (i == 0 ? 60 : width) + 0.01f,
                        "line " + i + " fits: " + lines.get(i));
            assertEquals(safe.replace(" ", ""), String.join("", lines).replace(" ", ""),
                    "every character is kept, in order");
            assertEquals("ngay", lines.getLast().substring(lines.getLast().lastIndexOf(' ') + 1));
        }
    }

    @Test
    void wordsFillALineGreedilyAndEmptyTextIsOneEmptyLine() throws Exception {
        try (var document = new PDDocument()) {
            var text = new PdfText(document);
            var font = text.font("HankenGrotesk-Regular.ttf");
            float width = PdfText.width("một hai", font, 10) + 1;

            assertEquals(List.of(List.of("một", "hai"), List.of("ba")),
                    PdfText.words(text.safe("một  hai ba", font), font, 10, width, width),
                    "a double space is one gap, and a line takes words while they fit");
            assertEquals(List.of(List.of()), PdfText.words("", font, 10, width, width));
        }
    }

    @Test
    void textIsComposedAndACharacterTheFaceLacksBecomesAQuestionMark() throws Exception {
        try (var document = new PDDocument()) {
            var text = new PdfText(document);
            var font = text.font("HankenGrotesk-Regular.ttf");
            String decomposed = "Nguyễn";

            assertEquals("Nguyễn", text.safe(decomposed, font), "marks land on the precomposed glyph");
            assertEquals("Họp ?", text.safe("Họp 😀", font), "an emoji the face lacks is one question mark");
        }
    }
}
