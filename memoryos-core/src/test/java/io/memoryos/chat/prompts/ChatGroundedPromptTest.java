package io.memoryos.chat.prompts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class ChatGroundedPromptTest {
    @Test
    void aGroundedInferenceIsToldToLeaveUncitedTheReplyThatTheDocumentsDoNotCoverTheQuestion() {
        var prompt = new Prompt(List.of(new UserMessage("Doanh thu quý 3 là bao nhiêu?")));

        var guided = ChatPrompts.forInference(prompt, true, false, false, true);

        assertTrue(guided.getInstructions().getFirst() instanceof SystemMessage system
                && system.getText().contains(ChatPrompts.GROUNDED_GUIDANCE));
        // MEM-317: a cited "the documents do not cover it" passes the citation gate and hides MSG-25
        assertTrue(ChatPrompts.GROUNDED_GUIDANCE.contains("do not cover it, without any citation"));
        assertTrue(ChatPrompts.GROUNDED_GUIDANCE.contains("Cite only evidence that supports the statement"));
    }
}
