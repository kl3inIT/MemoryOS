package io.memoryos.chat.grounding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CitationGateTest {
    private static final Function<String, String> NO_PHRASES = text -> null;

    private static CitationGate grounded(int sources) {
        return new CitationGate(true, id -> id >= 1 && id <= sources, NO_PHRASES, 0);
    }

    private static String run(CitationGate gate, String... chunks) {
        var shown = new StringBuilder();
        for (String chunk : chunks) shown.append(gate.accept(chunk));
        return shown.toString();
    }

    @Test
    void holdsEverythingUntilTheFirstValidCitationThenStreams() {
        var gate = grounded(2);
        assertEquals("", gate.accept("Theo quy chế nhân sự "));
        assertEquals("Theo quy chế nhân sự [1], nhân viên", gate.accept("[1], nhân viên"));
        assertEquals(" được nghỉ 12 ngày.", gate.accept(" được nghỉ 12 ngày."));
        assertEquals(CitationGate.Outcome.ANSWERED, gate.finish().outcome());
    }

    @Test
    void aCitationSplitAcrossChunksIsRecognised() {
        var gate = grounded(12);
        String shown = run(gate, "Doanh thu tăng [1", "2] trong quý.");
        var ending = gate.finish();
        assertEquals("Doanh thu tăng [12] trong quý.", shown + ending.text());
        assertEquals(CitationGate.Outcome.ANSWERED, ending.outcome());
    }

    @Test
    void anUnknownCitationIsDroppedAndDoesNotReleaseTheAnswer() {
        var gate = grounded(1);
        assertEquals("", gate.accept("Việt Nam có 34 tỉnh [9]."));
        var ending = gate.finish();
        assertEquals(CitationGate.Outcome.UNCITED, ending.outcome());
        assertEquals("", ending.text());
    }

    @Test
    void unknownCitationsAfterAValidOneAreRemovedFromTheShownText() {
        var gate = grounded(1);
        String shown = run(gate, "Quy định [1] và thêm [7] nữa.");
        assertEquals("Quy định [1] và thêm  nữa.", shown + gate.finish().text());
    }

    @Test
    void anAnswerWithoutAnyCitationIsNeverShown() {
        var gate = grounded(3);
        assertEquals("", run(gate, "Vợ bác Hồ ", "là một câu hỏi lịch sử."));
        assertEquals(CitationGate.Outcome.UNCITED, gate.finish().outcome());
    }

    @Test
    void aTrailingOpenBracketIsNotHeldForever() {
        var gate = grounded(1);
        String shown = run(gate, "Theo [1] thì mảng [quan trọng");
        assertEquals("Theo [1] thì mảng [quan trọng", shown + gate.finish().text());
    }

    @Test
    void anUngroundedTurnPassesTextThroughUntouched() {
        var gate = new CitationGate(false, id -> false, NO_PHRASES, 0);
        assertEquals("Năm [2024] tốt.", run(gate, "Năm [2024]", " tốt."));
        assertEquals(CitationGate.Outcome.ANSWERED, gate.finish().outcome());
    }

    @Test
    void aBlockedPhraseSplitAcrossChunksStopsTheAnswer() {
        var phrases = List.of("Dự án Phoenix");
        Function<String, String> match = text -> phrases.stream()
                .filter(phrase -> text.toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT))).findFirst().orElse(null);
        var gate = new CitationGate(false, id -> false, match, "Dự án Phoenix".length());
        String shown = run(gate, "Chi tiết về Dự án Phoe", "nix như sau");
        var ending = gate.finish();
        assertEquals(CitationGate.Outcome.BLOCKED, ending.outcome());
        assertEquals("Dự án Phoenix", ending.phrase());
        // Never more than the phrase length minus one of it was released.
        assertEquals(false, shown.contains("Dự án Phoenix"));
    }

    @Test
    void textWithoutABlockedPhraseIsFullyReleasedAtTheEnd() {
        Function<String, String> match = text -> text.contains("Phoenix") ? "Phoenix" : null;
        var gate = new CitationGate(false, id -> false, match, 7);
        String shown = run(gate, "Kế hoạch ", "quý ba");
        var ending = gate.finish();
        assertEquals("Kế hoạch quý ba", shown + ending.text());
        assertNull(ending.phrase());
    }
}
