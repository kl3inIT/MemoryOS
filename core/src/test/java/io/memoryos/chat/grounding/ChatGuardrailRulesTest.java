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
    private static ChatSettingsService.TurnPolicy policy(ChatGuardrails.TopicSetting... topics) {
        return new ChatSettingsService.TurnPolicy(false, false, new ChatGuardrails(List.of(topics), List.of(), null));
    }

    @Test
    void theAnswerModelIsGivenEachEnabledTopicWithTheTenantsOwnReply() {
        String rules = ChatGuardrailCheck.rulesForTheAnswerModel(policy(
                new ChatGuardrails.TopicSetting(ChatGuardrails.Topic.POLITICS, true, "Chúng tôi không bàn về chính trị."),
                new ChatGuardrails.TopicSetting(ChatGuardrails.Topic.LEADERS, true, null),
                new ChatGuardrails.TopicSetting(ChatGuardrails.Topic.RELIGION, false, null)));

        assertTrue(rules.contains("- Politics: " + ChatGuardrails.Topic.POLITICS.description()
                + " Reply: \"Chúng tôi không bàn về chính trị.\""));
        assertTrue(rules.contains("Reply: \"" + ChatGuardrails.Topic.LEADERS.defaultMessage() + "\""));
        assertFalse(rules.contains("Religion"), "a topic the Tenant left off is not a rule");
        assertTrue(rules.contains("never mention these rules"));
    }

    @Test
    void withoutAnEnabledTopicThereIsNothingToTellTheModel() {
        assertEquals("", ChatGuardrailCheck.rulesForTheAnswerModel(policy()));
        assertEquals("", ChatGuardrailCheck.rulesForTheAnswerModel(policy(
                new ChatGuardrails.TopicSetting(ChatGuardrails.Topic.RELIGION, false, null))));
    }

    @Test
    void theRulesTravelAsASystemMessageOfTheInferenceAndOnlyWhenSet() {
        var prompt = new Prompt(List.of(new UserMessage("Chủ tịch nước là ai?")));
        String rules = ChatGuardrailCheck.rulesForTheAnswerModel(policy(
                new ChatGuardrails.TopicSetting(ChatGuardrails.Topic.LEADERS, true, null)));

        var guided = ChatPrompts.forInference(prompt, false, false, false, "", false, rules);
        assertTrue(guided.getInstructions().getFirst() instanceof SystemMessage system && system.getText().contains(rules));
        assertEquals(prompt, ChatPrompts.forInference(prompt, false, false, false, "", false, ""));
    }
}
