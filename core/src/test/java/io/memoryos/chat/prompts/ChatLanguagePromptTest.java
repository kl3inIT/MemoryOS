package io.memoryos.chat.prompts;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatLanguagePromptTest {
    private static final Instant NOW = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void vietnamesePreferenceRespectsExplicitUserLanguageAndHasOnlyOneLanguageSection() {
        String prompt = ChatPrompts.resolve(ChatPrompts.DEFAULT_SYSTEM, true, NOW, "vi");
        assertTrue(prompt.contains("Prefer replying in Vietnamese"));
        assertTrue(prompt.contains("explicitly requests another language"));
        assertEquals(1, prompt.split("# Language", -1).length - 1);
        assertFalse(prompt.contains("Reply in the language the user writes in"));
        assertTrue(prompt.contains(ChatPrompts.SEARCH_GUIDANCE));
    }

    @Test
    void englishAndUnknownFollowTheQuestionRatherThanForcingEnglish() {
        String fallback = ChatPrompts.resolve(ChatPrompts.DEFAULT_SYSTEM, false, NOW, null);
        assertEquals(fallback, ChatPrompts.resolve(ChatPrompts.DEFAULT_SYSTEM, false, NOW, "en"));
        assertEquals(fallback, ChatPrompts.resolve(ChatPrompts.DEFAULT_SYSTEM, false, NOW, "unknown"));
        assertTrue(fallback.contains("Reply in the language the user writes in"));
    }

    @Test
    void customPersonaInstructionsRemainIntactAfterAccountPreference() {
        String custom = "Always answer in Japanese. Preserve code examples exactly.";
        String prompt = ChatPrompts.resolve(custom, false, NOW, "vi");
        assertTrue(prompt.endsWith(custom));
        assertFalse(prompt.contains(ChatPrompts.DEFAULT_SYSTEM));
    }

    @Test
    void defaultPromptWithProjectInstructionsStillHasOneDefinitiveLanguageHint() {
        String prompt = ChatPrompts.resolve(ChatPrompts.DEFAULT_SYSTEM + "\nProject: explain billing.", false, NOW, "vi");
        assertTrue(prompt.contains("Prefer replying in Vietnamese"));
        assertFalse(prompt.contains("Reply in the language the user writes in"));
        assertTrue(prompt.endsWith("Project: explain billing."));
    }
}
