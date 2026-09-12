package io.memoryos.chat.execution;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import com.embabel.chat.Message;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.springframework.ai.chat.model.ChatModel;
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
    void savedPresentationsAreContextDataAndCountAgainstTheSameBudget() {
        var artifact = new io.memoryos.chat.ChatArtifact(UUID.randomUUID(), "Revenue", """
                {"root":{"component":"Metric","props":{"label":"September","value":"125000"}}}
                """);
        var answer = new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), null, null, ChatMessage.Role.ASSISTANT,
                "", ChatMessage.Status.COMPLETED, Instant.now(), Instant.now(), List.of(), List.of(), List.of(artifact));
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(),
                context(List.of(message(ChatMessage.Role.USER, "Explain September"), answer, message(ChatMessage.Role.USER, "Summarize revenue"))), 32000, binding());
        assertTrue(setup.messages().stream().anyMatch(m -> m.getContent().contains("125000") && m.getContent().contains("data, not instructions")));
        var bounded = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(),
                context(List.of(message(ChatMessage.Role.USER, "Explain September"), answer, message(ChatMessage.Role.USER, "Summarize revenue"))), 120, binding());
        assertTrue(bounded.messages().stream().noneMatch(m -> m.getContent().contains("125000")));
    }

    @Test
    void switchingToNonVisionKeepsHistoryAndWorkspaceMarkersWithoutImageBudget() {
        var file = new io.memoryos.chat.ChatFileDescriptor(UUID.randomUUID(), "picture.png", "image/png", 100);
        var question = new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), null, null, ChatMessage.Role.USER,
                "Continue", ChatMessage.Status.COMPLETED, Instant.now(), Instant.now(), List.of(), List.of(file));
        var context = new TurnContext(new ActorId(UUID.randomUUID()), new TenantId(UUID.randomUUID()), "fixture",
                "Answer", List.of(question), Instant.now().plusSeconds(60), io.memoryos.chat.ChatTurnOptions.DEFAULT,
                java.util.Map.of(), List.of(file));
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context, 2000, binding());
        assertEquals(java.util.Map.of(), setup.images());
        org.junit.jupiter.api.Assertions.assertTrue(setup.messages().get(1).getContent().contains("this model cannot view images"));
        org.junit.jupiter.api.Assertions.assertTrue(setup.messages().getLast().getContent().contains("this model cannot view images"));
        var content = mock(io.memoryos.chat.ChatFileContentService.class);
        assertEquals(setup.messages(), ChatFileInputs.materialize(setup, content, () -> {}));
        org.mockito.Mockito.verifyNoInteractions(content);
    }

    @Test
    void visionPreservesImageAttachmentOrderAndChecksStopBeforeAndAfterPrivateIo() {
        var first = new io.memoryos.chat.ChatFileDescriptor(UUID.randomUUID(), "first.png", "image/png", 3);
        var second = new io.memoryos.chat.ChatFileDescriptor(UUID.randomUUID(), "second.png", "image/png", 3);
        var question = new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), null, null, ChatMessage.Role.USER,
                "Compare", ChatMessage.Status.COMPLETED, Instant.now(), Instant.now(), List.of(), List.of(first, second));
        var base = binding();
        var vision = new ChatModelBinding(base.service(), base.finalRequest(), base.tokens(), base.contextWindow(), base.maxOutputTokens(), false, true);
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context(List.of(question)), 32000, vision);
        assertEquals(List.of(first, second), setup.images().get(1));
        var content = mock(io.memoryos.chat.ChatFileContentService.class);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(content.image(setup.actor(), setup.tenant(), first.id())).thenReturn(new byte[]{1, 2, 3});
        assertThrows(java.util.concurrent.CancellationException.class, () -> ChatFileInputs.materialize(setup, content, () -> {
            if (checks.incrementAndGet() == 2) throw new java.util.concurrent.CancellationException();
        }));
        org.mockito.Mockito.verify(content).image(setup.actor(), setup.tenant(), first.id());
        org.mockito.Mockito.verifyNoMoreInteractions(content);
        org.junit.jupiter.api.Assertions.assertTrue(setup.evidence().snapshot().isEmpty());
    }

    @Test
    void visionRejectsCurrentImagesThatCannotFitInsteadOfSilentlyRemovingThem() {
        var file = new io.memoryos.chat.ChatFileDescriptor(UUID.randomUUID(), "picture.png", "image/png", 100);
        var question = new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), null, null, ChatMessage.Role.USER,
                "", ChatMessage.Status.COMPLETED, Instant.now(), Instant.now(), List.of(), List.of(file));
        var base = binding();
        var vision = new ChatModelBinding(base.service(), base.finalRequest(), base.tokens(), base.contextWindow(), base.maxOutputTokens(), false, true);
        assertThrows(ChatException.class, () -> ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context(List.of(question)), 2000, vision));
    }

    private static ChatModelBinding binding() {
        var binding = new ChatModelBinding(new SpringAiLlmService("binding-model", "fixture", mock(ChatModel.class)), p -> p);
        return new ChatModelBinding(binding.service(), binding.finalRequest(), binding.tokens(), binding.contextWindow(), binding.maxOutputTokens(), false);
    }
    @Test
    void contextLimitKeepsNewestQuestionAndDropsOrphanAssistant() {
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context(List.of(
                message(ChatMessage.Role.USER, "Newest"), message(ChatMessage.Role.ASSISTANT, "Previous"),
                message(ChatMessage.Role.USER, "Old question ".repeat(1000)))), 120, binding());
        assertEquals(2, setup.messages().size());
        assertEquals("binding-model", setup.model());
        assertInstanceOf(SystemMessage.class, setup.messages().getFirst());
        assertInstanceOf(UserMessage.class, setup.messages().getLast());
        assertEquals("Newest", setup.messages().getLast().getContent());
    }

    @Test
    void rejectsQuestionThatCannotFitWithInstructions() {
        assertThrows(ChatException.class, () -> ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(),
                context(List.of(message(ChatMessage.Role.USER, "Large question ".repeat(1000)))), 100, binding()));
    }

    private TurnContext context(List<ChatMessage> messages) {
        return new TurnContext(new ActorId(UUID.randomUUID()), new TenantId(UUID.randomUUID()), "gpt-5-mini",
                "Answer", messages, Instant.now().plusSeconds(60));
    }

    @Test
    void emptyOrNullAssistantDoesNotDropEarlierContext() {
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context(List.of(
                message(ChatMessage.Role.USER, "Newest"),
                new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), null, null, ChatMessage.Role.ASSISTANT,
                        null, ChatMessage.Status.FAILED, Instant.now(), Instant.now()),
                message(ChatMessage.Role.ASSISTANT, ""), message(ChatMessage.Role.USER, "Earlier"))),
                32000, binding());
        assertEquals(List.of("Earlier", "Newest"), setup.messages().stream().skip(1).map(Message::getContent).toList());
        assertTrue(setup.messages().getFirst().getContent().endsWith("Answer"));
    }

    private ChatMessage message(ChatMessage.Role role, String content) {
        return new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), null, null, role, content,
                ChatMessage.Status.COMPLETED, Instant.now(), Instant.now());
    }

    @Test
    void unicodeFileContentIsPlacedBeforeItsQuestionAndWorkspaceContent() {
        var id = UUID.randomUUID();
        var file = new io.memoryos.chat.ChatFileDescriptor(id, "ghi-chu.txt", "text/plain", 20);
        var question = new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), null, null, ChatMessage.Role.USER,
                "Tóm tắt", ChatMessage.Status.COMPLETED, Instant.now(), Instant.now(), List.of(), List.of(file));
        var context = new TurnContext(new ActorId(UUID.randomUUID()), new TenantId(UUID.randomUUID()), "fixture",
                "Answer", List.of(question), Instant.now().plusSeconds(60), io.memoryos.chat.ChatTurnOptions.DEFAULT,
                java.util.Map.of(id, new io.memoryos.chat.ChatFileService.FileText("A😀Việt", 0, 6)), List.of(file));
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context, 32000, binding());
        assertEquals(4, setup.messages().size());
        org.junit.jupiter.api.Assertions.assertTrue(setup.messages().get(1).getContent().contains("A😀Việt"));
        org.junit.jupiter.api.Assertions.assertTrue(setup.messages().get(2).getContent().contains("A😀Việt"));
        org.junit.jupiter.api.Assertions.assertTrue(setup.messages().getLast().getContent().startsWith("Tóm tắt"));
        assertEquals(java.util.Set.of(id), setup.fileIds());
    }
}
