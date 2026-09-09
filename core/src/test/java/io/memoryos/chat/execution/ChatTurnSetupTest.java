package io.memoryos.chat.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.application.ChatTurnPersistence.TurnContext;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ChatTurnSetupTest {
    @Test
    void contextLimitKeepsNewestQuestionAndDropsOrphanAssistant() {
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context(List.of(
                message(ChatMessage.Role.USER, "Newest"), message(ChatMessage.Role.ASSISTANT, "Previous"),
                message(ChatMessage.Role.USER, "Old question ".repeat(1000)))), 120, mock(ChatModelBinding.class));
        assertEquals(2, setup.messages().size());
        assertInstanceOf(SystemMessage.class, setup.messages().getFirst());
        assertInstanceOf(UserMessage.class, setup.messages().getLast());
        assertEquals("Newest", setup.messages().getLast().getContent());
    }

    @Test
    void rejectsQuestionThatCannotFitWithInstructions() {
        assertThrows(ChatException.class, () -> ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(),
                context(List.of(message(ChatMessage.Role.USER, "Large question ".repeat(1000)))), 100, mock(ChatModelBinding.class)));
    }

    private TurnContext context(List<ChatMessage> messages) {
        return new TurnContext(new ActorId(UUID.randomUUID()), new TenantId(UUID.randomUUID()), "gpt-5-mini",
                "Answer", messages, Instant.now().plusSeconds(60));
    }

    private ChatMessage message(ChatMessage.Role role, String content) {
        return new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), null, null, role, content,
                ChatMessage.Status.COMPLETED, Instant.now(), Instant.now());
    }
}
