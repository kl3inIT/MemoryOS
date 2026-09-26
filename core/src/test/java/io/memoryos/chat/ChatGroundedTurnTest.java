package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.knuddels.jtokkit.api.EncodingType;
import io.memoryos.ai.ModelBinding;
import io.memoryos.ai.ModelClients;
import io.memoryos.ai.ModelRequestPolicy;
import io.memoryos.ai.ModelResolver;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.grounding.ChatGuardrailCheck;
import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.streaming.ChatStreamProperties;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.chat.streaming.TestRedis;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

/** MEM-195: a grounded turn answers only with a citation, and a blocked question never reaches the answer model. */
class ChatGroundedTurnTest {
    private final ChatTurnPersistence persistence = mock(ChatTurnPersistence.class);
    private final ChatModelExecutor model = mock(ChatModelExecutor.class);
    private final ChatModelSelector models = mock(ChatModelSelector.class);
    private final ChatSettingsService settings = mock(ChatSettingsService.class);
    private final ChatGuardrailCheck guardrails = mock(ChatGuardrailCheck.class);
    private final ModelClients.Lease lease = mock(ModelClients.Lease.class);
    private final ChatExecutionProperties limits = new ChatExecutionProperties(1, Duration.ofMinutes(30), Duration.ofSeconds(60),
            Duration.ofSeconds(60), 6, 1024, 32000, 10000, null, null, 10, Duration.ofSeconds(60));
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final StreamBufferWriter streams = new StreamBufferWriter(TestRedis.template(),
            new ChatStreamProperties(4096, Duration.ofMinutes(60), Duration.ofMinutes(10), 512, Duration.ofMillis(25), 4, 8, 2048,
                    Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofMinutes(1)));
    private final UUID session = UUID.randomUUID();
    private final UUID parent = UUID.randomUUID();
    private final ChatTurnPersistence.Reservation pair = new ChatTurnPersistence.Reservation(UUID.randomUUID(), UUID.randomUUID(), true);

    private void prepare(boolean toolCalling, ChatSettingsService.TurnPolicy policy, ChatGuardrailCheck.Kind kind) {
        var binding = new ModelBinding(new SpringAiLlmService("gpt-5-mini", "fixture", mock(ChatModel.class)), p -> p,
                ModelRequestPolicy.hosted(new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), p -> p), 32000, 4096, toolCalling, false);
        when(lease.binding()).thenReturn(binding);
        when(models.resolve(any(), any(), any(), any())).thenReturn(new ModelResolver.Resolved(UUID.randomUUID(), null, lease));
        when(persistence.finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> new ChatTurnPersistence.TerminalOutcome(call.getArgument(2), call.getArgument(4)));
        when(persistence.existing(any(), any(), any(ChatCommand.class))).thenReturn(Optional.empty());
        // The agent itself does not search; grounded mode adds the search tool only while it applies.
        var grounded = new ChatTurnOptions(false, List.of(), null, null).withGrounded(true);
        when(persistence.agent(any(), any())).thenReturn(new ChatTurnPersistence.SessionAgent(new JdbcChatRepository.Persona(
                "", "gpt-5-mini", grounded, "0", null, List.of(), Set.of("search", "web_search"), null), false, false, false));
        when(persistence.reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any())).thenReturn(pair);
        var question = new ChatMessage(pair.userMessageId(), session, parent, pair.assistantMessageId(), ChatMessage.Role.USER,
                "Vợ bác Hồ là ai?", ChatMessage.Status.COMPLETED, Instant.now(), Instant.now());
        when(persistence.loadContext(any(), any(), any())).thenReturn(new ChatTurnPersistence.TurnContext(actor,
                new TenantId(UUID.randomUUID()), "gpt-5-mini", "Answer", List.of(question), grounded, java.util.Map.of(), List.of(), null));
        when(settings.turnPolicy(any())).thenReturn(policy);
        when(settings.read(any())).thenReturn(new ChatSettingsService.View(true, ChatHistoryVisibility.NORMAL, true, policy.groundedAllowWeb(), 0));
        when(guardrails.check(any(), any(), any(), any())).thenReturn(new ChatGuardrailCheck.Result(kind,
                kind == ChatGuardrailCheck.Kind.BLOCKED ? "Trợ lý không trả lời câu hỏi về lãnh tụ." : null,
                kind == ChatGuardrailCheck.Kind.BLOCKED ? ChatGuardrails.Topic.LEADERS : null, null));
    }

    private ChatTurnService service(AtomicReference<Runnable> queued) {
        return new ChatTurnService(persistence, model, limits, queued::set, streams, models, null, null, settings, null, null, null, guardrails);
    }

    private static final ChatSettingsService.TurnPolicy GROUNDED = new ChatSettingsService.TurnPolicy(true, false, ChatGuardrails.NONE);

    private void answers(String text, ChatSource... sources) {
        doAnswer(call -> {
            Consumer<ChatActivityEvent> events = call.getArgument(5);
            for (var source : sources) events.accept(new ChatToolEvent(ChatEvidence.FILE_CONTEXT, source));
            call.<Consumer<String>>getArgument(3).accept(text);
            return null;
        }).when(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void verifyStored(String content, String refusal) {
        verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.COMPLETED), eq(content),
                isNull(), eq("gpt-5-mini"), isNull(), isNull(), isNull(), any(), any(), eq(ChatResearch.EMPTY),
                refusal == null ? isNull() : eq(refusal), any());
    }

    @Test
    void anAnswerWithoutACitationIsReplacedByTheRefusal() {
        prepare(true, GROUNDED, ChatGuardrailCheck.Kind.QUESTION);
        answers("Việt Nam hiện có 34 tỉnh, thành phố.");
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Vợ bác Hồ là ai?", null);
            queued.get().run();
            verifyStored("Tài liệu của tổ chức chưa có thông tin để trả lời câu hỏi này.", ChatMessage.NO_EVIDENCE);
        }
    }

    @Test
    void anAnswerCitingRegisteredEvidenceIsReleasedAndTheTurnIsGrounded() {
        prepare(true, GROUNDED, ChatGuardrailCheck.Kind.QUESTION);
        var source = new ChatSource(1, null, null, "Quy chế nhân sự.pdf", 0, 0, List.of(), UUID.randomUUID(), null, null,
                "application/pdf", List.of(), null);
        answers("Theo quy chế nhân sự [1], nhân viên được nghỉ 12 ngày.", source);
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Nghỉ phép năm?", null);
            queued.get().run();
            verifyStored("Theo quy chế nhân sự [1], nhân viên được nghỉ 12 ngày.", null);
            var setup = ArgumentCaptor.forClass(ChatTurnSetup.class);
            verify(model).execute(setup.capture(), any(), any(), any(), any(), any(), any(), any(), any());
            assertEquals(true, setup.getValue().options().grounded());
            assertEquals(true, setup.getValue().options().searches());
        }
    }

    @Test
    void aBlockedQuestionIsAnsweredWithTheTenantMessageWithoutTheAnswerModel() {
        prepare(true, GROUNDED, ChatGuardrailCheck.Kind.BLOCKED);
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Vợ bác Hồ là ai?", null);
            queued.get().run();
            verify(model, never()).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(guardrails).recordBlock(any(), any(), any());
            verifyStored("Trợ lý không trả lời câu hỏi về lãnh tụ.", ChatMessage.BLOCKED_TOPIC);
        }
    }

    @Test
    void aGreetingIsAnsweredWithoutTheCitationRule() {
        prepare(true, GROUNDED, ChatGuardrailCheck.Kind.CONVERSATIONAL);
        answers("Chào bạn! Mình có thể giúp gì?");
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Xin chào", null);
            queued.get().run();
            verifyStored("Chào bạn! Mình có thể giúp gì?", null);
            var setup = ArgumentCaptor.forClass(ChatTurnSetup.class);
            verify(model).execute(setup.capture(), any(), any(), any(), any(), any(), any(), any(), any());
            assertFalse(setup.getValue().options().grounded());
            assertFalse(setup.getValue().options().searches(), "a greeting does not widen the agent's tools");
        }
    }

    @Test
    void admissionRefusesWhatAGroundedTurnCannotDo() {
        prepare(false, GROUNDED, ChatGuardrailCheck.Kind.QUESTION);
        try (var service = service(new AtomicReference<>())) {
            assertEquals("CHAT_GROUNDED_MODEL_UNSUPPORTED", assertThrows(ChatException.class,
                    () -> service.send(actor, session, parent, UUID.randomUUID(), "Q", null)).code());
            var research = new ChatCommand(ChatCommand.Operation.SEND, parent, UUID.randomUUID(), "Q", null, List.of(),
                    WebSearchMode.off, ImageMode.off, true);
            assertEquals("CHAT_RESEARCH_UNAVAILABLE", assertThrows(ChatException.class,
                    () -> service.command(actor, session, research)).code());
            var web = new ChatCommand(ChatCommand.Operation.SEND, parent, UUID.randomUUID(), "Q", null, List.of(),
                    WebSearchMode.auto, ImageMode.off, false);
            assertEquals("CHAT_WEB_UNAVAILABLE", assertThrows(ChatException.class,
                    () -> service.command(actor, session, web)).code());
            verify(persistence, never()).reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any());
        }
    }
}
