package io.memoryos.chat.execution;

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
    void switchingToNonVisionKeepsHistoryAndWorkspaceMarkersWithoutImageBudget() {
        var file = new io.memoryos.chat.ChatFileDescriptor(UUID.randomUUID(), "picture.png", "image/png", 100);
        var question = new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), null, null, ChatMessage.Role.USER,
                "Continue", ChatMessage.Status.COMPLETED, Instant.now(), Instant.now(), List.of(), List.of(file));
        var context = new TurnContext(new ActorId(UUID.randomUUID()), new TenantId(UUID.randomUUID()), "fixture",
                "Answer", List.of(question), Instant.now().plusSeconds(60), io.memoryos.chat.ChatTurnOptions.DEFAULT,
                java.util.Map.of(), List.of(file));
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context, 2000, binding(), "");
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
        var vision = new ChatModelBinding(base.service(), base.finalRequest(), base.policy(), base.contextWindow(), base.maxOutputTokens(), false, true);
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context(List.of(question)), 32000, vision, "");
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
        var vision = new ChatModelBinding(base.service(), base.finalRequest(), base.policy(), base.contextWindow(), base.maxOutputTokens(), false, true);
        assertThrows(ChatException.class, () -> ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context(List.of(question)), 2000, vision, ""));
    }

    private static ChatModelBinding binding() {
        return new ChatModelBinding(new SpringAiLlmService(
                "binding-model", "fixture", mock(ChatModel.class)), p -> p, ChatRequestPolicy.hosted(
        new org.springframework.ai.tokenizer.JTokkitTokenCountEstimator(com.knuddels.jtokkit.api.EncodingType.O200K_BASE), p -> p), 32000, 4096, false, false);
    }
    @Test
    void contextLimitKeepsNewestQuestionAndDropsOrphanAssistant() {
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context(List.of(
                message(ChatMessage.Role.USER, "Newest"), message(ChatMessage.Role.ASSISTANT, "Previous"),
                message(ChatMessage.Role.USER, "Old question ".repeat(1000)))), 120, binding(), "");
        assertEquals(2, setup.messages().size());
        assertEquals("binding-model", setup.model());
        assertInstanceOf(SystemMessage.class, setup.messages().getFirst());
        assertInstanceOf(UserMessage.class, setup.messages().getLast());
        assertEquals("Newest", setup.messages().getLast().getContent());
    }

    @Test
    void rejectsQuestionThatCannotFitWithInstructions() {
        assertThrows(ChatException.class, () -> ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(),
                context(List.of(message(ChatMessage.Role.USER, "Large question ".repeat(1000)))), 100, binding(), ""));
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
                32000, binding(), "");
        assertEquals(List.of("Answer", "Earlier", "Newest"), setup.messages().stream().map(Message::getContent).toList());
    }

    @Test
    void frozenFrameworkContributionSharesTheUnicodeHistoryBoundary() {
        var binding = binding();
        String question = "Hãy giải thích cách lưu trữ tài liệu.";
        String contribution = "Current date: 2026-09-11\n";
        String system = ChatTurnSetup.instructions("Answer", contribution);
        int exact = binding.policy().framing().applyAsInt(new org.springframework.ai.chat.prompt.Prompt(List.of(
                new org.springframework.ai.chat.messages.SystemMessage(system), new org.springframework.ai.chat.messages.UserMessage(question))));
        var context = context(List.of(message(ChatMessage.Role.USER, question), message(ChatMessage.Role.ASSISTANT, "Older")));
        assertThrows(ChatException.class, () -> ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context, exact - 1, binding, contribution));
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context, exact, binding, contribution);
        assertEquals(List.of(system, question), setup.messages().stream().map(Message::getContent).toList());
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
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context, 32000, binding(), "");
        assertEquals(4, setup.messages().size());
        org.junit.jupiter.api.Assertions.assertTrue(setup.messages().get(1).getContent().contains("A😀Việt"));
        org.junit.jupiter.api.Assertions.assertTrue(setup.messages().get(2).getContent().contains("A😀Việt"));
        org.junit.jupiter.api.Assertions.assertTrue(setup.messages().getLast().getContent().startsWith("Tóm tắt"));
        assertEquals(java.util.Set.of(id), setup.fileIds());
    }

    @Test
    void attachmentFramingFallsBackToFileToolsWithoutPublishingUnseenEvidence() {
        var file = new io.memoryos.chat.ChatFileDescriptor(UUID.randomUUID(), "notes.txt", "text/plain", 4);
        var question = new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), null, null, ChatMessage.Role.USER,
                "Summarize", ChatMessage.Status.COMPLETED, Instant.now(), Instant.now(), List.of(), List.of(file));
        var context = new TurnContext(new ActorId(UUID.randomUUID()), new TenantId(UUID.randomUUID()), "fixture",
                "Answer", List.of(question), Instant.now().plusSeconds(60), io.memoryos.chat.ChatTurnOptions.DEFAULT,
                java.util.Map.of(file.id(), new io.memoryos.chat.ChatFileService.FileText("text", 0, 4)), List.of());
        var base = binding();
        var policy = new ChatRequestPolicy(base.policy().tokens(),
                prompt -> base.policy().framing().applyAsInt(prompt)
                        + Math.max(0, prompt.getInstructions().size() - 2) * 10000,
                prompt -> prompt, ignored -> {});
        var tools = new ChatModelBinding(base.service(), base.finalRequest(), policy, 32000, 4096, true, false);
        var setup = ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context, 2000, tools, "");
        assertEquals(java.util.Set.of(file.id()), setup.fileIds());
        org.junit.jupiter.api.Assertions.assertTrue(setup.messages().getLast().getContent().startsWith("Summarize"));
        org.junit.jupiter.api.Assertions.assertTrue(setup.evidence().snapshot().isEmpty());
        var noTools = new ChatModelBinding(base.service(), base.finalRequest(), policy, 32000, 4096, false, false);
        assertThrows(ChatException.class, () -> ChatTurnSetup.resolve(UUID.randomUUID(), UUID.randomUUID(), context, 2000, noTools, ""));
    }
}
