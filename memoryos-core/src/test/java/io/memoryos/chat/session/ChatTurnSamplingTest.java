package io.memoryos.chat.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.memoryos.ai.ModelSampling;
import io.memoryos.ai.ReasoningEffort;
import io.memoryos.chat.ChatPreferences;
import io.memoryos.chat.ChatTurnOptions;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * MEM-273: a turn's reasoning level is the conversation's pinned level or the agent's own (the persona read coalesces
 * them), either outranking the model configuration, and only then the member's default, which yields to a level the
 * model configuration names.
 */
class ChatTurnSamplingTest {
    @Test
    void theConversationOrAgentLevelOutranksTheModelAndTheMemberDefaultDoesNot() {
        assertEquals(new ModelSampling(null, ReasoningEffort.HIGH, true),
                ChatTurnPersistence.sampling(persona(ReasoningEffort.HIGH), member(ReasoningEffort.LOW)));
        assertEquals(new ModelSampling(null, ReasoningEffort.LOW, false),
                ChatTurnPersistence.sampling(persona(null), member(ReasoningEffort.LOW)));
        assertEquals(new ModelSampling(0.3, ReasoningEffort.OFF, true),
                ChatTurnPersistence.sampling(persona(ReasoningEffort.OFF), new ChatPreferences("", "", null, 0.3, null, true)));
        assertEquals(ModelSampling.NONE, ChatTurnPersistence.sampling(persona(null), ChatPreferences.DEFAULT));
    }

    private static JdbcChatRepository.Persona persona(@Nullable ReasoningEffort effort) {
        return new JdbcChatRepository.Persona("", ChatTurnOptions.DEFAULT, "r", null, List.of(), Set.of(), List.of(), effort);
    }

    private static ChatPreferences member(ReasoningEffort effort) {
        return new ChatPreferences("", "", null, null, effort, true);
    }
}
