package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ModelBinding;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProvider;
import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.ai.ModelClients;
import io.memoryos.ai.ModelFlow;
import io.memoryos.ai.ModelRequestPolicy;
import io.memoryos.ai.ModelResolver;
import io.memoryos.ai.TurnFailure;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.grounding.ChatGuardrailCheck;
import io.memoryos.chat.grounding.GroundingClassifier;
import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.streaming.ChatStreamProperties;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.chat.streaming.TestRedis;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.shared.Tokenizers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.typesafe.response.Usage;
import org.springframework.ai.chat.model.ChatModel;

/** MEM-195: a grounded turn answers only with a citation, and a blocked question never reaches the answer model. */
class ChatGroundedTurnTest {
    private final ChatTurnPersistence persistence = mock(ChatTurnPersistence.class);
    private final ChatModelExecutor model = mock(ChatModelExecutor.class);
    private final ChatModelSelector models = mock(ChatModelSelector.class);
    private final ChatSettingsService settings = mock(ChatSettingsService.class);
    private final ChatGuardrailCheck guardrails = mock(ChatGuardrailCheck.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
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

    /** What the fixture binding's adapter reads as a refused credential. */
    private static final class Refused extends RuntimeException {
        Refused() {
            super("Incorrect API key provided: sk-fixture");
        }
    }

    /** Messages before the question in the context, newest first, as the conversation's history holds them. */
    private List<ChatMessage> earlierNewestFirst = List.of();

    private void prepare(boolean toolCalling, ChatSettingsService.TurnPolicy policy, ChatGuardrailCheck.Kind kind) {
        var binding = ModelBinding.builder(new SpringAiLlmService("gpt-5-mini", "fixture", mock(ChatModel.class)), p -> p,
                ModelRequestPolicy.hosted(Tokenizers.o200k(), p -> p), 32000, 4096, toolCalling, false)
                .credentialRejection(failure -> failure instanceof Refused || failure.getCause() instanceof Refused).build();
        when(lease.binding()).thenReturn(binding);
        when(models.resolve(any(), any(), any(), any())).thenReturn(new ModelResolver.Resolved(UUID.randomUUID(), null, lease));
        // The guardrail task model; a test that needs its own model replaces this.
        when(models.resolveFlow(any(), any(), eq(ModelFlow.CHAT_GUARDRAIL)))
                .thenAnswer(call -> new ModelResolver.Resolved(UUID.randomUUID(), null, lease));
        when(persistence.finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> new ChatTurnPersistence.TerminalOutcome(call.getArgument(2), call.getArgument(4)));
        when(persistence.existing(any(), any(), any(ChatCommand.class))).thenReturn(Optional.empty());
        // The agent itself does not search; grounded mode adds the search tool only while it applies.
        var grounded = ChatTurnOptions.builder().searchEnabled(false).grounded(true).build();
        when(persistence.agent(any(), any())).thenReturn(new ChatTurnPersistence.SessionAgent(new JdbcChatRepository.Persona(
                "", grounded, "0", null, List.of(), Set.of("search", "web_search"), null), false, false, false));
        when(persistence.reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any())).thenReturn(pair);
        var question = ChatMessage.builder(pair.userMessageId(), session, ChatMessage.Role.USER, ChatMessage.Status.COMPLETED, Instant.now())
                .parentMessageId(parent)
                .latestChildMessageId(pair.assistantMessageId())
                .content("Vợ bác Hồ là ai?")
                .finishedAt(Instant.now())
                .build();
        when(persistence.loadContext(any(), any(), any())).thenReturn(ChatTurnPersistence.TurnContext.builder(actor,
                new TenantId(UUID.randomUUID()), "Answer",
                Stream.concat(Stream.of(question), earlierNewestFirst.stream()).toList())
                .options(grounded)
                .build());
        when(settings.turnPolicy(any())).thenReturn(policy);
        when(settings.read(any())).thenReturn(new ChatSettingsService.View(true, ChatHistoryVisibility.NORMAL, true, policy.groundedAllowWeb(), 0));
        when(guardrails.check(any(), any(), any(), any(), any(), any())).thenReturn(new ChatGuardrailCheck.Result(kind,
                kind == ChatGuardrailCheck.Kind.BLOCKED ? "Trợ lý không trả lời câu hỏi về lãnh tụ." : null,
                kind == ChatGuardrailCheck.Kind.BLOCKED ? ChatGuardrails.BUILT_IN.get(1) : null, null));
    }

    private ChatTurnService service(AtomicReference<Runnable> queued) {
        return new ChatTurnService(persistence, model, limits, queued::set, streams, models, null, null, settings, null, null, null, guardrails,
                new ChatTurnMetrics(meters));
    }

    private static final ChatSettingsService.TurnPolicy GROUNDED = new ChatSettingsService.TurnPolicy(true, false, ChatGuardrails.NONE);

    private void answers(String text, ChatSource... sources) {
        doAnswer(call -> {
            Consumer<ChatActivityEvent> events = call.getArgument(5);
            for (var source : sources) events.accept(ChatToolEvent.source(ChatEvidence.FILE_CONTEXT, source));
            call.<Consumer<String>>getArgument(3).accept(text);
            return null;
        }).when(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void verifyStored(String content, String refusal) {
        verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.COMPLETED), eq(content),
                isNull(), eq("gpt-5-mini"), isNull(), isNull(), isNull(), any(), any(), eq(ChatResearch.EMPTY),
                refusal == null ? isNull() : eq(refusal), any());
    }

    private void verifyFailed(String code) {
        verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.FAILED), anyString(),
                eq(code), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aCredentialTheProviderRefusesWhileAnsweringFailsTheTurnWithItsOwnCode() {
        prepare(true, GROUNDED, ChatGuardrailCheck.Kind.QUESTION);
        doThrow(new Refused()).when(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Vợ bác Hồ là ai?", null);
            queued.get().run();
            verifyFailed("CHAT_PROVIDER_CREDENTIAL_REJECTED");
        }
    }

    private static final ChatSettingsService.TurnPolicy TOPICS = new ChatSettingsService.TurnPolicy(true, false,
            new ChatGuardrails(ChatGuardrails.BUILT_IN.subList(0, 2).stream().map(topic -> new ChatGuardrails.Topic(topic.id(),
                    topic.name(), topic.description(), topic.examples(), topic.message(), true)).toList(), List.of(), null));

    /** The guardrail task runs on its own model, so a test can fail it apart from the conversation model. */
    private ModelBinding taskModel() {
        var checker = ModelBinding.builder(new SpringAiLlmService("gpt-5-nano", "fixture", mock(ChatModel.class)), p -> p,
                ModelRequestPolicy.hosted(Tokenizers.o200k(), p -> p), 32000, 4096, true, false).build();
        var checkerLease = mock(ModelClients.Lease.class);
        when(checkerLease.binding()).thenReturn(checker);
        when(models.resolveFlow(any(), any(), eq(ModelFlow.CHAT_GUARDRAIL)))
                .thenReturn(new ModelResolver.Resolved(UUID.randomUUID(), null, checkerLease));
        return checker;
    }

    @Test
    void aCheckThatFailsIsNotAskedAgainOnAnotherModel() {
        prepare(true, TOPICS, ChatGuardrailCheck.Kind.QUESTION);
        var checker = taskModel();
        when(guardrails.check(eq(checker), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException(new Refused()));
        answers("Việt Nam hiện có 34 tỉnh, thành phố.");
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Vợ bác Hồ là ai?", null);
            queued.get().run();
            // As no guardrail project falls back to a second classifier: the answer model carries the rules instead.
            verify(guardrails).check(any(), any(), any(), any(), any(), any());
            var setup = ArgumentCaptor.forClass(ChatTurnSetup.class);
            verify(model).execute(setup.capture(), any(), any(), any(), any(), any(), any(), any(), any());
            assertTrue(setup.getValue().options().topicRules().contains("Trợ lý không trả lời câu hỏi về lãnh tụ và lãnh đạo."));
        }
    }

    @Test
    void theCheckReadsTheConversationBeforeTheQuestion() {
        var asked = ChatMessage.builder(UUID.randomUUID(), session, ChatMessage.Role.USER, ChatMessage.Status.COMPLETED, Instant.now())
                .content("Chủ tịch nước hiện nay là ai?").build();
        var declined = ChatMessage.builder(UUID.randomUUID(), session, ChatMessage.Role.ASSISTANT, ChatMessage.Status.COMPLETED,
                Instant.now()).content("Trợ lý không trả lời câu hỏi về lãnh tụ và lãnh đạo.").build();
        earlierNewestFirst = List.of(declined, asked);
        prepare(true, TOPICS, ChatGuardrailCheck.Kind.BLOCKED);
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Vợ bác Hồ là ai?", null);
            queued.get().run();
            verify(guardrails).check(any(), any(), eq("Vợ bác Hồ là ai?"), eq(List.of(asked, declined)), any(), any());
            verifyStored("Trợ lý không trả lời câu hỏi về lãnh tụ.", ChatMessage.BLOCKED_TOPIC);
        }
    }

    @Test
    void withTopicsToBlockAQuestionNobodyCouldCheckIsAnsweredByAModelThatCarriesTheTopicRules() {
        prepare(true, TOPICS, ChatGuardrailCheck.Kind.QUESTION);
        when(guardrails.check(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("no verdict"));
        answers("Việt Nam hiện có 34 tỉnh, thành phố.");
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Vợ bác Hồ là ai?", null);
            queued.get().run();
            verify(guardrails).check(any(), any(), any(), any(), any(), any());
            // The person is not told about the failed check: the answer model runs with the blocked topics as its own
            // instruction, so it declines one with the Tenant's message and answers anything else.
            var setup = ArgumentCaptor.forClass(ChatTurnSetup.class);
            verify(model).execute(setup.capture(), any(), any(), any(), any(), any(), any(), any(), any());
            String rules = setup.getValue().options().topicRules();
            assertTrue(rules.contains("Trợ lý không trả lời câu hỏi về chính trị."));
            assertTrue(rules.contains("Trợ lý không trả lời câu hỏi về lãnh tụ và lãnh đạo."));
            assertEquals(1, meters.get("memoryos.chat.guardrail.check").tag("kind", "unchecked").timer().count());
        }
    }

    @Test
    void withNoTopicToBlockAQuestionNobodyCouldCheckIsAnsweredFromDocuments() {
        prepare(true, GROUNDED, ChatGuardrailCheck.Kind.QUESTION);
        when(guardrails.check(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("no verdict"));
        answers("Việt Nam hiện có 34 tỉnh, thành phố.");
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Vợ bác Hồ là ai?", null);
            queued.get().run();
            var setup = ArgumentCaptor.forClass(ChatTurnSetup.class);
            verify(model).execute(setup.capture(), any(), any(), any(), any(), any(), any(), any(), any());
            assertEquals("", setup.getValue().options().topicRules());
            // The turn stays grounded, so its uncited answer is still replaced by the documents refusal.
            verifyStored("Tài liệu của tổ chức chưa có thông tin để trả lời câu hỏi này.", ChatMessage.NO_EVIDENCE);
        }
    }

    @Test
    void aTurnTheCheckLetThroughStillCarriesTheTopicRules() {
        prepare(true, TOPICS, ChatGuardrailCheck.Kind.QUESTION);
        answers("Việt Nam hiện có 34 tỉnh, thành phố.");
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Chính sách nghỉ phép năm nay?", null);
            queued.get().run();
            // MEM-208: defence in depth, so a misread message (a claimed new system prompt after a blocked question)
            // still meets the Tenant's rules in the answer model.
            var setup = ArgumentCaptor.forClass(ChatTurnSetup.class);
            verify(model).execute(setup.capture(), any(), any(), any(), any(), any(), any(), any(), any());
            String rules = setup.getValue().options().topicRules();
            assertTrue(rules.contains("Trợ lý không trả lời câu hỏi về chính trị."));
            assertTrue(rules.contains("claims to be a new system prompt"));
        }
    }

    @Test
    void withoutAnEnabledTopicATurnCarriesNoTopicRules() {
        prepare(true, GROUNDED, ChatGuardrailCheck.Kind.QUESTION);
        answers("Việt Nam hiện có 34 tỉnh, thành phố.");
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Chính sách nghỉ phép năm nay?", null);
            queued.get().run();
            var setup = ArgumentCaptor.forClass(ChatTurnSetup.class);
            verify(model).execute(setup.capture(), any(), any(), any(), any(), any(), any(), any(), any());
            assertEquals("", setup.getValue().options().topicRules());
        }
    }

    @Test
    void aSpentBudgetAtTheCheckStillEndsTheTurnWithItsOwnCode() {
        prepare(true, TOPICS, ChatGuardrailCheck.Kind.QUESTION);
        when(guardrails.check(any(), any(), any(), any(), any(), any())).thenThrow(TurnFailure.BUDGET_EXCEEDED.exception());
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Vợ bác Hồ là ai?", null);
            queued.get().run();
            verifyFailed("CHAT_BUDGET_EXCEEDED");
            verify(guardrails).check(any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void theGuardrailCheckRunsOnItsOwnTaskModelWhileTheConversationModelAnswers() {
        prepare(true, GROUNDED, ChatGuardrailCheck.Kind.QUESTION);
        answers("Việt Nam hiện có 34 tỉnh, thành phố.");
        var checker = ModelBinding.builder(new SpringAiLlmService("gpt-5-nano", "fixture", mock(ChatModel.class)), p -> p,
                ModelRequestPolicy.hosted(Tokenizers.o200k(), p -> p), 32000, 4096, true, false).build();
        var checkerLease = mock(ModelClients.Lease.class);
        when(checkerLease.binding()).thenReturn(checker);
        UUID checkerModel = UUID.randomUUID();
        when(models.resolveFlow(any(), any(), eq(ModelFlow.CHAT_GUARDRAIL)))
                .thenReturn(new ModelResolver.Resolved(checkerModel, null, checkerLease));
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Vợ bác Hồ là ai?", null);
            queued.get().run();
            verify(guardrails).check(eq(checker), any(), any(), any(), any(), any());
            verify(model).execute(argThat(setup -> setup.binding() != checker), any(), any(), any(), any(), any(), any(), any(), any());
            verify(checkerLease).close();
        }
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
            // The dashboard counts the refusal by reason, and the refusal is the first text the person saw.
            assertEquals(1, meters.get("memoryos.chat.turn").tags("status", "completed", "refusal", "no_evidence", "grounded", "true",
                    "failure", "none", "research", "false").timer().count());
            assertEquals(1, meters.get("memoryos.chat.turn.first.text").tag("grounded", "true").timer().count());
            assertEquals(1, meters.get("memoryos.chat.guardrail.check").tag("kind", "question").timer().count());
        }
    }

    @Test
    void anAnswerCitingRegisteredEvidenceIsReleasedAndTheTurnIsGrounded() {
        prepare(true, GROUNDED, ChatGuardrailCheck.Kind.QUESTION);
        var source = ChatSource.file(1, UUID.randomUUID(), "Quy chế nhân sự.pdf", "application/pdf", null);
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
            verify(guardrails).recordBlock(any(), any(), any(), any(), any());
            verifyStored("Trợ lý không trả lời câu hỏi về lãnh tụ.", ChatMessage.BLOCKED_TOPIC);
            assertEquals(1, meters.get("memoryos.chat.turn").tag("refusal", "blocked_topic").timer().count());
            assertEquals(1, meters.get("memoryos.chat.guardrail.check").tag("kind", "blocked").timer().count());
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
            var research = ChatCommand.builder(ChatCommand.Operation.SEND, parent, UUID.randomUUID(), "Q")
                    .deepResearch(true)
                    .build();
            assertEquals("CHAT_RESEARCH_UNAVAILABLE", assertThrows(ChatException.class,
                    () -> service.command(actor, session, research)).code());
            var web = ChatCommand.builder(ChatCommand.Operation.SEND, parent, UUID.randomUUID(), "Q")
                    .webSearch(WebSearchMode.auto)
                    .build();
            assertEquals("CHAT_WEB_UNAVAILABLE", assertThrows(ChatException.class,
                    () -> service.command(actor, session, web)).code());
            verify(persistence, never()).reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any());
        }
    }

    private static final SystemOneConnectionService.Connection SERVING = new SystemOneConnectionService.Connection(
            UUID.randomUUID(), UUID.randomUUID(), SystemOneProvider.NINEROUTER, "9Router",
            "http://9router.internal:20128/v1", "openrouter/typesafe/jev-1.13", "v1:stored", DataBoundary.EXTERNAL, 0.042);

    @Test
    void aCheckOnASystemOneConnectionLeasesNoLanguageModelAndIsRecordedUnderTheConnection() {
        prepare(true, TOPICS, ChatGuardrailCheck.Kind.QUESTION);
        when(guardrails.connection(any())).thenReturn(SERVING);
        when(guardrails.checkOn(eq(SERVING), any(), any(), any(), any(), any())).thenAnswer(call -> {
            Consumer<Usage> used = call.getArgument(5);
            used.accept(new Usage(1_000_000, null));
            return new ChatGuardrailCheck.Result(ChatGuardrailCheck.Kind.BLOCKED, "Trợ lý không trả lời câu hỏi về lãnh tụ.",
                    ChatGuardrails.BUILT_IN.get(1), null);
        });
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Vợ bác Hồ là ai?", null);
            queued.get().run();
            verify(models, never()).resolveFlow(any(), any(), any());
            verify(guardrails, never()).check(any(), any(), any(), any(), any(), any());
            verifyStored("Trợ lý không trả lời câu hỏi về lãnh tụ.", ChatMessage.BLOCKED_TOPIC);
            var usage = ArgumentCaptor.forClass(ChatTurnPersistence.Usage.class);
            verify(persistence).recordUsage(usage.capture());
            assertEquals("9Router", usage.getValue().provider().providerName());
            assertEquals("EXTERNAL", usage.getValue().provider().dataBoundary());
            assertEquals("openrouter/typesafe/jev-1.13", usage.getValue().modelName());
            assertEquals(null, usage.getValue().modelConfigurationId());
            // A service that reports its input only wrote no output; the cost is the input at the connection's price.
            assertEquals(1_000_000L, usage.getValue().accounting().input());
            assertEquals(0L, usage.getValue().accounting().output());
            assertEquals(0.042, usage.getValue().accounting().cost(), 1e-9);
            assertEquals(1, meters.get("memoryos.chat.guardrail.check").tag("kind", "blocked")
                    .tag("classifier", "system_one").timer().count());
        }
    }

    @Test
    void aTopicScoredBetweenTheThresholdsLetsTheTurnThroughAndIsCountedAsAReview() {
        prepare(true, TOPICS, ChatGuardrailCheck.Kind.QUESTION);
        when(guardrails.check(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ChatGuardrailCheck.Result(ChatGuardrailCheck.Kind.QUESTION, null, null, null, true));
        answers("Việt Nam hiện có 34 tỉnh, thành phố.");
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Việt Nam có bao nhiêu tỉnh?", null);
            queued.get().run();
            verify(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
            assertEquals(1, meters.get("memoryos.chat.guardrail.check").tag("kind", "question").tag("classifier", "llm")
                    .tag("review", "true").timer().count());
        }
    }

    @Test
    void aSystemOneCheckThatFailsIsNotAskedAgainOnALanguageModel() {
        prepare(true, TOPICS, ChatGuardrailCheck.Kind.QUESTION);
        when(guardrails.connection(any())).thenReturn(SERVING);
        when(guardrails.checkOn(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("unreachable"));
        answers("Việt Nam hiện có 34 tỉnh, thành phố.");
        var queued = new AtomicReference<Runnable>();
        try (var service = service(queued)) {
            service.send(actor, session, parent, UUID.randomUUID(), "Việt Nam có bao nhiêu tỉnh?", null);
            queued.get().run();
            verify(models, never()).resolveFlow(any(), any(), any());
            verify(guardrails, never()).check(any(), any(), any(), any(), any(), any());
            verify(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
            assertEquals(1, meters.get("memoryos.chat.guardrail.check").tag("kind", "unchecked")
                    .tag("classifier", "system_one").timer().count());
        }
    }

    @Test
    void aBlockIsRecordedInItsOwnTransactionBecauseAChatTurnHasNone() {
        // Staging, 2026-09-27: record() requires the caller's transaction, so every blocked turn failed instead of
        // answering with the Tenant's message.
        var audit = mock(AuditTrail.class);
        var check = new ChatGuardrailCheck(mock(GroundingClassifier.class), audit, null);
        check.recordBlock(new TenantId(UUID.randomUUID()), actor, session,
                new ChatGuardrailCheck.Result(ChatGuardrailCheck.Kind.BLOCKED, "Không trả lời.", ChatGuardrails.BUILT_IN.get(1), null), null);
        var event = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).recordSeparately(event.capture());
        verify(audit, never()).record(any());
        assertEquals(AuditAction.CHAT_GUARDRAIL_BLOCK, event.getValue().action());
    }
}
