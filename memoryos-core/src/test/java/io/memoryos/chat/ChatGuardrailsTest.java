package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChatGuardrailsTest {
    private static ChatGuardrails.Topic topic(String name, boolean enabled) {
        return new ChatGuardrails.Topic(UUID.randomUUID(), name, "Câu hỏi về " + name + ".", List.of(), "", enabled);
    }

    @Test
    void aTenantStartsFromTheThreeBuiltInTopicsAllOff() {
        var seed = ChatGuardrails.DEFAULT;
        assertEquals(List.of("Chính trị", "Lãnh tụ và lãnh đạo", "Tôn giáo"),
                seed.topics().stream().map(ChatGuardrails.Topic::name).toList());
        assertTrue(seed.enabledTopics().isEmpty());
        assertFalse(seed.active());
        assertEquals("Trợ lý không trả lời câu hỏi về tôn giáo.", seed.topics().get(2).message());
    }

    @Test
    void aTopicIsTrimmedItsDescriptionFoldedOntoOneLineAndBlankExamplesDropped() {
        var edited = ChatGuardrails.of(List.of(new ChatGuardrails.Topic(UUID.randomUUID(), "  Lương thưởng ",
                "Câu hỏi về lương\n\nhoặc thưởng   của từng người.", List.of(" Lương CEO bao nhiêu? ", " "), "  ", true)),
                List.of(), null).topics().getFirst();
        assertEquals("Lương thưởng", edited.name());
        assertEquals("Câu hỏi về lương hoặc thưởng của từng người.", edited.description());
        assertEquals(List.of("Lương CEO bao nhiêu?"), edited.examples());
        assertEquals(ChatGuardrails.DEFAULT_TOPIC_MESSAGE, edited.message());
    }

    @Test
    void phrasesAreTrimmedDeduplicatedAndMatchedIgnoringCase() {
        var rules = ChatGuardrails.of(List.of(), List.of("  Dự án Phoenix ", "dự án phoenix", "", "Mã nội bộ"), null);
        assertEquals(List.of("Dự án Phoenix", "Mã nội bộ"), rules.blockedPhrases());
        assertEquals("Dự án Phoenix", rules.blockedPhraseIn("Cho tôi biết về DỰ ÁN PHOENIX"));
        assertNull(rules.blockedPhraseIn("Kế hoạch quý ba"));
        assertEquals("Dự án Phoenix".length(), rules.longestPhrase());
        assertTrue(rules.active());
        assertEquals(ChatGuardrails.DEFAULT_PHRASE_MESSAGE, rules.blockedPhraseMessage());
    }

    @Test
    void decomposedVietnameseAccentsStillMatchABlockedPhrase() {
        var rules = ChatGuardrails.of(List.of(), List.of(Normalizer.normalize("Dự án Phoenix", Normalizer.Form.NFD)), null);
        assertEquals("Dự án Phoenix", rules.blockedPhrases().getFirst());
        assertEquals("Dự án Phoenix", rules.blockedPhraseIn(Normalizer.normalize("Kể về dự án Phoenix đi", Normalizer.Form.NFD)));
        assertEquals("Dự án Phoenix", rules.blockedPhraseIn("Kể về DỰ ÁN PHOENIX đi"));
    }

    @Test
    void anEditBeyondTheBoundsIsRefused() {
        var many = Collections.nCopies(21, "x").stream().map(value -> value + Math.random()).toList();
        assertThrows(ChatException.class, () -> ChatGuardrails.of(List.of(), many, null));
        assertThrows(ChatException.class, () -> ChatGuardrails.of(List.of(), List.of("a".repeat(101)), null));
        assertThrows(ChatException.class, () -> ChatGuardrails.of(List.of(topic("Lương", true), topic(" lương ", false)),
                List.of(), null), "two topics may not share a name");
        var same = topic("Lương", true);
        assertThrows(ChatException.class, () -> ChatGuardrails.of(List.of(same, same), List.of(), null));
        assertThrows(ChatException.class, () -> ChatGuardrails.of(List.of(topic("x".repeat(37), true)), List.of(), null));
        assertThrows(ChatException.class, () -> ChatGuardrails.of(List.of(new ChatGuardrails.Topic(UUID.randomUUID(), "Lương",
                "x".repeat(351), List.of(), "", true)), List.of(), null));
        assertThrows(ChatException.class, () -> ChatGuardrails.of(List.of(new ChatGuardrails.Topic(UUID.randomUUID(), "Lương",
                "Lương.", List.of("1", "2", "3", "4", "5", "6"), "", true)), List.of(), null));
        assertThrows(ChatException.class, () -> ChatGuardrails.of(List.of(new ChatGuardrails.Topic(UUID.randomUUID(), "Lương",
                " ", List.of(), "", true)), List.of(), null), "a topic needs a description to be classified by");
        var thirtyOne = IntStream.range(0, 31).mapToObj(index -> topic("Chủ đề " + index, false)).toList();
        assertThrows(ChatException.class, () -> ChatGuardrails.of(thirtyOne, List.of(), null));
    }
}
