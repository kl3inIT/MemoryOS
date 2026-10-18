package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The catalogue ships with the build: every file loads and fits an assistant's limits in both languages. */
class AssistantTemplatesTest {
    @Test
    void everyTemplateLoadsInDisplayOrderWithBothLanguages() {
        var templates = AssistantTemplates.all();

        assertEquals(AssistantTemplates.KEYS, templates.stream().map(AssistantTemplate::key).toList());
        assertEquals(Set.copyOf(AssistantTemplates.CATEGORIES),
                Set.copyOf(templates.stream().map(AssistantTemplate::category).toList()),
                "Every category Khám phá groups by has a template");
        templates.forEach(template -> assertEquals(template, AssistantTemplates.check(template.key(), template)));
        assertEquals("Hỏi đáp chính sách nội bộ", AssistantTemplates.find("policy-qa").orElseThrow().text("vi").name());
        assertEquals("Internal policy Q&A", AssistantTemplates.find("policy-qa").orElseThrow().text("en").name());
    }

    @Test
    void aTemplateOutsideAnAssistantsLimitsIsRefused() {
        var email = AssistantTemplates.find("email").orElseThrow();
        var tooManyStarters = new AssistantTemplate.Text("Email", "Email", "Email", "Hello",
                List.of("1", "2", "3", "4", "5"));
        var unknownTool = new AssistantTemplate(email.key(), email.category(), email.avatarSeed(), Set.of("telepathy"),
                false, email.vi(), email.en(), email.instructions());
        var englishStarters = new AssistantTemplate(email.key(), email.category(), email.avatarSeed(), email.tools(),
                false, email.vi(), tooManyStarters, email.instructions());

        assertThrows(IllegalStateException.class, () -> AssistantTemplates.check("email", unknownTool));
        assertThrows(IllegalStateException.class, () -> AssistantTemplates.check("email", englishStarters));
        assertThrows(IllegalStateException.class, () -> AssistantTemplates.check("other", email));
    }
}
