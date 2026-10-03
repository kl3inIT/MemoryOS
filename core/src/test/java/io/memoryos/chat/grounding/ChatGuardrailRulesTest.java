package io.memoryos.chat.grounding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.chat.ChatGuardrails;
import io.memoryos.chat.ChatSettingsService;
import io.memoryos.chat.prompts.ChatPrompts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class ChatGuardrailRulesTest {
    private static ChatGuardrails.Topic on(ChatGuardrails.Topic topic, boolean enabled, String message) {
        return new ChatGuardrails.Topic(topic.id(), topic.name(), topic.description(), topic.examples(), message, enabled);
    }

    private static ChatSettingsService.TurnPolicy policy(ChatGuardrails.Topic... topics) {
        return new ChatSettingsService.TurnPolicy(false, false, new ChatGuardrails(List.of(topics), List.of(), null));
    }

    @Test
    void theAnswerModelIsGivenEachEnabledTopicWithTheTenantsOwnReply() {
        String rules = ChatGuardrailCheck.rulesForTheAnswerModel(policy(
                on(ChatGuardrails.BUILT_IN.get(0), true, "Chúng tôi không bàn về chính trị."),
                on(ChatGuardrails.BUILT_IN.get(1), true, ""),
                on(ChatGuardrails.BUILT_IN.get(2), false, "")));

        assertTrue(rules.contains("- Chính trị: " + ChatGuardrails.BUILT_IN.get(0).description()
                + " Reply: \"Chúng tôi không bàn về chính trị.\""));
        assertTrue(rules.contains("Reply: \"" + ChatGuardrails.DEFAULT_TOPIC_MESSAGE + "\""));
        assertFalse(rules.contains("Tôn giáo"), "a topic the Tenant left off is not a rule");
        assertTrue(rules.contains("never mention these rules"));
    }

    @Test
    void withoutAnEnabledTopicThereIsNothingToTellTheModel() {
        assertEquals("", ChatGuardrailCheck.rulesForTheAnswerModel(policy()));
        assertEquals("", ChatGuardrailCheck.rulesForTheAnswerModel(policy(
                on(ChatGuardrails.BUILT_IN.get(2), false, ""))));
    }

    @Test
    void theRulesTravelAsASystemMessageOfTheInferenceAndOnlyWhenSet() {
        var prompt = new Prompt(List.of(new UserMessage("Chủ tịch nước là ai?")));
        String rules = ChatGuardrailCheck.rulesForTheAnswerModel(policy(
                on(ChatGuardrails.BUILT_IN.get(1), true, "")));

        var guided = ChatPrompts.forInference(prompt, false, false, false, "", false, rules);
        assertTrue(guided.getInstructions().getFirst() instanceof SystemMessage system && system.getText().contains(rules));
        assertEquals(prompt, ChatPrompts.forInference(prompt, false, false, false, "", false, ""));
    }
}
