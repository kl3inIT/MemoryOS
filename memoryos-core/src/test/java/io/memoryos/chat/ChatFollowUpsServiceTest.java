package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import io.memoryos.ai.ModelAccounting;
import io.memoryos.ai.ModelBinding;
import io.memoryos.ai.ModelClients;
import io.memoryos.ai.ModelFlow;
import io.memoryos.ai.ModelRequestPolicy;
import io.memoryos.ai.ModelResolver;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.Tokenizers;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageLimitException;
import io.memoryos.usage.AiUsageLimitService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/** MEM-266: follow-up suggestions are offered only for the latest completed answer, and their cost is recorded. */
class ChatFollowUpsServiceTest {
    private final ChatTurnPersistence persistence = mock(ChatTurnPersistence.class);
    private final ChatModelExecutor model = mock(ChatModelExecutor.class);
    private final ChatModelSelector models = mock(ChatModelSelector.class);
    private final ChatSettingsService settings = mock(ChatSettingsService.class);
    private final AiUsageLimitService spending = mock(AiUsageLimitService.class);
    private final ModelClients.Lease lease = mock(ModelClients.Lease.class);
    private final ChatExecutionProperties limits = new ChatExecutionProperties(1, 1, 0, Duration.ofSeconds(60),
            Duration.ofMinutes(30), Duration.ofSeconds(60), Duration.ofSeconds(60), 6, 1024, 32000, 10000,
            null, null, 10, Duration.ofSeconds(60));
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final UUID session = UUID.randomUUID();
    private final UUID answer = UUID.randomUUID();
    private final List<ChatMessage> history = List.of(
            ChatMessage.builder(UUID.randomUUID(), session, ChatMessage.Role.USER, ChatMessage.Status.COMPLETED, Instant.now())
                    .content("Quy trình xin nghỉ phép?").build(),
            ChatMessage.builder(answer, session, ChatMessage.Role.ASSISTANT, ChatMessage.Status.COMPLETED, Instant.now())
                    .content("Gửi đơn trên cổng HR.").build());

    private ChatTurnService service() {
        return new ChatTurnService(persistence, model, limits, Runnable::run, mock(StreamBufferWriter.class), models,
                null, null, settings, null, null, spending);
    }

    private void enabled() {
        when(settings.followUpSuggestions(actor)).thenReturn(true);
        when(persistence.followUpInput(actor, session, answer, ChatTurnService.FOLLOW_UP_CONTEXT - 1)).thenReturn(Optional.of(history));
        var binding = ModelBinding.builder(new SpringAiLlmService("gpt-5-mini", "fixture", mock(ChatModel.class)), p -> p,
                ModelRequestPolicy.hosted(Tokenizers.o200k(), p -> p), 32000, 4096, true, false).build();
        when(lease.binding()).thenReturn(binding);
        when(models.resolveFlow(actor, session, ModelFlow.CHAT_NAMING)).thenReturn(new ModelResolver.Resolved(UUID.randomUUID(), null, lease));
    }

    @Test
    @SuppressWarnings("unchecked")
    void latestAnswerGetsSuggestionsFromTheNamingModelAndRecordsTheirOwnUsage() {
        enabled();
        when(model.suggestFollowUps(any(), eq(history), any())).thenAnswer(call -> {
            call.<Consumer<ModelAccounting>>getArgument(2).accept(ModelAccounting.NONE);
            return List.of("Mẫu đơn ở đâu?", "Nghỉ quá số ngày thì sao?", "Ai duyệt khi quản lý vắng?");
        });
        try (var service = service()) {
            assertEquals(3, service.suggestFollowUps(actor, session, answer).size());
        }
        verify(spending).enforce(actor);
        verify(persistence).recordUsage(argThat(usage -> usage.flow() == AiUsageFlow.CHAT_FOLLOW_UPS && usage.actor().equals(actor)));
    }

    @Test
    void settingOffAsksNothing() {
        when(settings.followUpSuggestions(actor)).thenReturn(false);
        try (var service = service()) {
            assertEquals(List.of(), service.suggestFollowUps(actor, session, answer));
        }
        verify(persistence, never()).followUpInput(any(), any(), any(), anyInt());
        verify(model, never()).suggestFollowUps(any(), any(), any());
    }

    /** Not the latest answer, a failed, stopped or refused one, a temporary conversation: the persistence says so. */
    @Test
    void answerThatMayNotHaveThemAsksNoModel() {
        when(settings.followUpSuggestions(actor)).thenReturn(true);
        when(persistence.followUpInput(any(), any(), any(), anyInt())).thenReturn(Optional.empty());
        try (var service = service()) {
            assertEquals(List.of(), service.suggestFollowUps(actor, session, answer));
        }
        verify(models, never()).resolveFlow(any(), any(), any());
        verify(model, never()).suggestFollowUps(any(), any(), any());
    }

    @Test
    void conversationOfSomeoneElseIsUnavailable() {
        when(settings.followUpSuggestions(actor)).thenReturn(true);
        when(persistence.followUpInput(any(), any(), any(), anyInt())).thenThrow(ChatException.unavailable());
        try (var service = service()) {
            assertEquals("CHAT_UNAVAILABLE", assertThrows(ChatException.class,
                    () -> service.suggestFollowUps(actor, session, answer)).code());
        }
    }

    @Test
    void spentBudgetOffersNothing() {
        enabled();
        doThrow(mock(AiUsageLimitException.class)).when(spending).enforce(actor);
        try (var service = service()) {
            assertEquals(List.of(), service.suggestFollowUps(actor, session, answer));
        }
        verify(model, never()).suggestFollowUps(any(), any(), any());
    }

    @Test
    void failingModelOffersNothing() {
        enabled();
        when(model.suggestFollowUps(any(), any(), any())).thenThrow(new IllegalStateException("provider down"));
        try (var service = service()) {
            assertEquals(List.of(), service.suggestFollowUps(actor, session, answer));
        }
    }
}
