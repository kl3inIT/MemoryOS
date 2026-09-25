package io.memoryos.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SpeakerIntroductionsTest {
    private static final List<Meeting.Speaker> ASKING = List.of(new Meeting.Speaker(Meeting.Track.MIC, "1", null),
            new Meeting.Speaker(Meeting.Track.MIC, "2", null));

    private static Meeting.Utterance said(String label, String text) {
        return new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, label, 0, 1000, text, 0.9);
    }

    @Test void readsTheNameAVoiceGaveItselfAndTrustsAParticipantMore() {
        var first = said("1", "Chào mọi người, mình là Minh, phụ trách tài chính.");
        var second = said("2", "Em tên là Lan ạ.");
        var found = SpeakerIntroductions.suggest(List.of(first, second), List.of("Anh Minh"), ASKING);

        var one = found.get(SpeakerNames.key(Meeting.Track.MIC, "1"));
        var two = found.get(SpeakerNames.key(Meeting.Track.MIC, "2"));
        assertEquals("Minh", one.name());
        assertEquals(first.id(), one.utteranceId(), "the offer carries the line it was read from");
        assertEquals(0.9, one.confidence(), 1e-9, "nobody by that exact name was expected at the meeting");
        assertEquals("Lan", two.name());
        assertEquals(0.9, two.confidence(), 1e-9);

        var expected = SpeakerIntroductions.suggest(List.of(first), List.of("Minh"), ASKING);
        assertEquals(0.97, expected.get(SpeakerNames.key(Meeting.Track.MIC, "1")).confidence(), 1e-9,
                "a name on the participant list is all but certain");
    }

    @Test void keepsTheEarliestIntroductionAndIgnoresVoicesThatAreNotAsking() {
        var early = said("1", "Tôi là Minh.");
        var later = said("1", "Thật ra tôi là Minh Anh.");
        var other = said("3", "Mình là Hải.");
        var found = SpeakerIntroductions.suggest(List.of(early, later, other), List.of(), ASKING);

        assertEquals(1, found.size(), "a named or dismissed voice is left alone");
        assertEquals(early.id(), found.get(SpeakerNames.key(Meeting.Track.MIC, "1")).utteranceId());
    }

    @Test void readsNamesWrittenTheOtherWaysAndInEnglish() {
        assertEquals("Minh", name("Tên tôi là Minh."));
        assertEquals("Minh Anh", name("Minh Anh đây, xin chào cả nhà."));
        assertEquals("Sarah", name("Hi, my name is Sarah and I lead the audit."));
        assertEquals("Hải", name("Còn lại là số liệu quý 4. Mình là Hải nhé."));
    }

    @Test void refusesARoleOrASentenceThatIsNotAName() {
        assertNull(name("Tôi là nhân viên mới của phòng kế toán."));
        assertNull(name("Mình là người chốt ngân sách."));
        assertNull(name("tôi là minh"), "a name said without a capital is not read as one");
        assertTrue(SpeakerIntroductions.suggest(List.of(said("1", "Ngân sách quý 4 thì thứ Năm chốt.")), List.of(),
                ASKING).isEmpty());
    }

    private static @Nullable String name(String text) {
        var found = SpeakerIntroductions.suggest(List.of(said("1", text)), List.of(), ASKING);
        var suggestion = found.get(SpeakerNames.key(Meeting.Track.MIC, "1"));
        return suggestion == null ? null : suggestion.name();
    }
}
