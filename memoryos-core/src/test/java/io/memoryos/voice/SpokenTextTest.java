package io.memoryos.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The marks a reader sees are character offsets into the stored line, so every test here reads the marked text back
 * out of {@link SpokenText#said()} rather than trusting the numbers.
 */
class SpokenTextTest {
    @Test
    void aTokenTheProviderWasSureOfIsNotMarked() {
        var text = new SpokenText();
        text.append("Chốt ", 0.95);
        text.append("ngân sách.", 0.99);

        assertEquals("Chốt ngân sách.", text.said());
        assertEquals(0.97, text.confidence(), 0.001, "the mean covers every token, marked or not");
        assertTrue(text.spans().isEmpty());
    }

    @Test
    void anUncertainTokenIsMarkedWhereItIsRead() {
        var text = new SpokenText();
        text.append("Chốt ", 0.95);
        text.append("Tasco ", 0.42);
        text.append("quý 4.", 0.9);

        String said = text.said();
        assertEquals("Chốt Tasco quý 4.", said);
        var spans = text.spans();
        assertEquals(1, spans.size());
        assertEquals("Tasco", marked(said, spans.getFirst()), "the mark covers the word, not the space before it");
        assertEquals(0.42, spans.getFirst().confidence(), 0.001);
    }

    @Test
    void aLeadingSpaceOnTheFirstTokenDoesNotShiftTheMarks() {
        var text = new SpokenText();
        // Soniox writes a token's own leading space; the first one is stripped off the stored line.
        text.append(" Khê ", 0.3);
        text.append("rồi.", 0.95);

        String said = text.said();
        assertEquals("Khê rồi.", said);
        assertEquals("Khê", marked(said, text.spans().getFirst()));
    }

    @Test
    void neighbouringUncertainTokensReadAsOneStretch() {
        var text = new SpokenText();
        text.append("Ông ", 0.95);
        text.append("dịch ", 0.4);
        text.append("nó ", 0.5);
        text.append("ra.", 0.95);

        String said = text.said();
        var spans = text.spans();
        assertEquals(1, spans.size(), "two uncertain tokens in a row are one mark");
        assertEquals("dịch nó", marked(said, spans.getFirst()));
        assertEquals(0.4, spans.getFirst().confidence(), 0.001, "the stretch carries the worst of them");
    }

    @Test
    void uncertainTokensApartStayApart() {
        var text = new SpokenText();
        text.append("Khê ", 0.4);
        text.append("rồi ", 0.95);
        text.append("nhé.", 0.5);

        String said = text.said();
        var spans = text.spans();
        assertEquals(2, spans.size());
        assertEquals("Khê", marked(said, spans.getFirst()));
        assertEquals("nhé.", marked(said, spans.get(1)));
    }

    @Test
    void aTokenAtTheThresholdIsLeftAlone() {
        var text = new SpokenText();
        text.append("Vừa đủ.", SpokenText.UNCERTAIN);

        assertTrue(text.spans().isEmpty(), "the threshold is the lowest confidence still trusted");
    }

    @Test
    void resettingForgetsTheMarksOfThePreviousUtterance() {
        var text = new SpokenText();
        text.append("Khê.", 0.3);
        text.reset();
        text.append("Rõ ràng.", 0.99);

        assertEquals("Rõ ràng.", text.said());
        assertEquals(List.of(), text.spans());
    }

    private static String marked(String said, LiveTranscription.Span span) {
        return said.substring(span.start(), span.end());
    }
}
