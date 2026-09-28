package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatGuardrailsTest {
    @Test
    void everyBuiltInTopicIsListedAndOffUntilTheTenantTurnsItOn() {
        var none = ChatGuardrails.NONE;
        assertEquals(ChatGuardrails.Topic.values().length, none.allTopics().size());
        assertTrue(none.enabledTopics().isEmpty());
        assertFalse(none.active());
        assertEquals(ChatGuardrails.Topic.RELIGION.defaultMessage(), none.topic(ChatGuardrails.Topic.RELIGION).message());
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
        var twice = List.of(new ChatGuardrails.TopicSetting(ChatGuardrails.Topic.LEADERS, true, null),
                new ChatGuardrails.TopicSetting(ChatGuardrails.Topic.LEADERS, false, null));
        assertThrows(ChatException.class, () -> ChatGuardrails.of(twice, List.of(), null));
    }
}
