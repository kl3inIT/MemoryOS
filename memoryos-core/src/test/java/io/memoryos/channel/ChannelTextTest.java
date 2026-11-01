package io.memoryos.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelTextTest {
    @Test
    void aTextThatFitsIsOnePieceAndNothingIsNoPiece() {
        assertEquals(List.of("Ngắn gọn."), ChannelText.pieces("  Ngắn gọn.  ", 2_000));
        assertEquals(List.of(), ChannelText.pieces(" \n ", 10));
    }

    @Test
    void aPieceEndsAtTheLastParagraphThenLineThenSentenceThenSpaceThatFits() {
        assertEquals(List.of("Một hai.", "Ba bốn."), ChannelText.pieces("Một hai.\n\nBa bốn.", 12));
        assertEquals(List.of("Một hai", "Ba bốn"), ChannelText.pieces("Một hai\nBa bốn", 10));
        assertEquals(List.of("Một. Hai.", "Ba bốn"), ChannelText.pieces("Một. Hai. Ba bốn", 12));
        assertEquals(List.of("một hai ba", "bốn"), ChannelText.pieces("một hai ba bốn", 12));
    }

    @Test
    void aWordLongerThanAPieceIsCutWhereTheLengthRunsOutButNeverInsideACharacter() {
        assertEquals(List.of("abcd", "efgh", "ij"), ChannelText.pieces("abcdefghij", 4));
        // An emoji is two chars; a cut between them would leave half a character in each piece.
        var pieces = ChannelText.pieces("abc😀def", 4);
        assertEquals(List.of("abc", "😀de", "f"), pieces);
    }

    @Test
    void everyPieceFitsAndTogetherTheyKeepEveryWord() {
        String text = "Chính sách nghỉ phép. ".repeat(300) + "\n\n" + "Thử việc thì sao? ".repeat(200);
        var pieces = ChannelText.pieces(text, 2_000);
        assertTrue(pieces.size() > 1);
        pieces.forEach(piece -> assertTrue(piece.length() <= 2_000 && !piece.isBlank()));
        assertEquals(text.replaceAll("\\s+", ""), String.join("", pieces).replaceAll("\\s+", ""));
        assertThrows(IllegalArgumentException.class, () -> ChannelText.pieces("text", 0));
    }
}
