package io.memoryos.chat;

import com.knuddels.jtokkit.api.EncodingType;
import io.memoryos.ai.ModelRequestPolicy;
import io.memoryos.ai.TurnFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.ai.ModelBinding;
import io.memoryos.ai.ModelResolver;
import io.memoryos.ai.ModelClients;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.streaming.TestRedis;
import java.util.Set;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import io.memoryos.chat.streaming.ChatStreamProperties;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.mcp.McpTurnService;
import io.memoryos.mcp.McpTurnTools;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import reactor.core.publisher.Mono;

class ChatTurnServiceTest {
    private final ChatTurnPersistence persistence = mock(ChatTurnPersistence.class);
    private final ChatModelExecutor model = mock(ChatModelExecutor.class);
    private final ChatModelSelector models = mock(ChatModelSelector.class);
    private final ModelClients.Lease lease = mock(ModelClients.Lease.class);
    private final ChatExecutionProperties limits = new ChatExecutionProperties(1, Duration.ofMinutes(30), Duration.ofSeconds(60), Duration.ofSeconds(60), 6, 1024, 32000, 10000,
            null, null, 10, Duration.ofSeconds(60));
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final StreamBufferWriter streams = new StreamBufferWriter(TestRedis.template(),
            new ChatStreamProperties(4096, Duration.ofMinutes(60), Duration.ofMinutes(10), 512, Duration.ofMillis(25), 4, 8, 2048,
                    Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofMinutes(1)));
    private final UUID session = UUID.randomUUID();
    private final UUID parent = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();
    private final ChatTurnPersistence.Reservation pair = new ChatTurnPersistence.Reservation(UUID.randomUUID(), UUID.randomUUID(), true);

    private void prepare() {
        var binding = new ModelBinding(new SpringAiLlmService("gpt-5-mini", "fixture", mock(ChatModel.class)), p -> p, ModelRequestPolicy.hosted(new JTokkitTokenCountEstimator(
                EncodingType.O200K_BASE), p -> p), 32000, 4096, true, false);
        when(lease.binding()).thenReturn(binding);
        when(models.resolve(any(), any(), any(), any())).thenReturn(new ModelResolver.Resolved(UUID.randomUUID(), null, lease));
        when(persistence.finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> new ChatTurnPersistence.TerminalOutcome(call.getArgument(2), call.getArgument(4)));
        when(persistence.existing(any(), any(), any(ChatCommand.class))).thenReturn(Optional.empty());
        // The builtin agent allows every tool and every MCP server the actor can use.
        when(persistence.agent(any(), any())).thenReturn(new ChatTurnPersistence.SessionAgent(new JdbcChatRepository.Persona(
                "", "gpt-5-mini", ChatTurnOptions.DEFAULT, "0", null, List.of(),
                Set.of("search", "web_search", "image_generation"), null, true), false, false, false));
        when(persistence.reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any())).thenReturn(pair);
        var question = new ChatMessage(pair.userMessageId(), session, parent, pair.assistantMessageId(), ChatMessage.Role.USER,
                "Question", ChatMessage.Status.COMPLETED, Instant.now(), Instant.now());
        when(persistence.loadContext(any(), any(), any())).thenReturn(new ChatTurnPersistence.TurnContext(actor,
                new TenantId(UUID.randomUUID()), "gpt-5-mini", "Answer", List.of(question)));
    }

    @Test
    void stopBeforeTaskStartsNeverInvokesModelAndAdmissionRejectsWithoutReserving() {
        prepare();
        var queued = new AtomicReference<Runnable>();
        try (var service = new ChatTurnService(persistence, model, limits, queued::set, streams, models)) {
            service.send(actor, session, parent, request, "Question", null);
            assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(ChatException.class,
                    () -> service.send(actor, session, parent, UUID.randomUUID(), "Question", null)).code());
            when(persistence.authorizeReply(actor, session, pair.assistantMessageId())).thenReturn(ChatMessage.Status.RUNNING);
            service.cancel(actor, session, pair.assistantMessageId());
            queued.get().run();
            verify(model, never()).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(persistence).reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any());
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.CANCELED), eq(""),
                    isNull(), eq("gpt-5-mini"), isNull(), isNull(), isNull(), eq(List.of()), any(), eq(ChatResearch.EMPTY), any());
        }
    }

    @Test
    void rejectedDispatchFinalizesReservationWithoutLeakingOrDuplicatingAdmission() {
        prepare();
        try (var service = new ChatTurnService(persistence, model, limits, ignored -> {
            throw new TaskRejectedException("shutdown");
        }, streams, models)) {
            assertThrows(TaskRejectedException.class, () -> service.send(actor, session, parent, request, "Question", null));
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.FAILED), eq(""),
                    eq("CHAT_SUBMIT_FAILED"), eq("gpt-5-mini"), isNull(), isNull(), isNull(), eq(List.of()), any(), eq(ChatResearch.EMPTY), any());
            verify(model, never()).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
            // A second rejected dispatch reaches the executor; it is not falsely rejected as capacity exhausted.
            when(persistence.reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any()))
                    .thenReturn(new ChatTurnPersistence.Reservation(pair.userMessageId(), UUID.randomUUID(), true));
            assertThrows(TaskRejectedException.class, () -> service.send(actor, session, parent, UUID.randomUUID(), "Question", null));
        }
    }

    @Test
    @SuppressWarnings("resource") // This Mockito stubbing does not acquire a real lease.
    void unavailableProviderRejectsBeforeReservation() {
        prepare();
        doThrow(ChatException.providerUnavailable()).when(models).resolve(any(), any(), any(), any());
        try (var service = new ChatTurnService(persistence, model, limits, Runnable::run, streams, models)) {
            assertEquals("CHAT_PROVIDER_UNAVAILABLE", assertThrows(ChatException.class,
                    () -> service.send(actor, session, parent, request, "Question", null)).code());
            verify(persistence, never()).reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any());
        }
    }

    @Test
    void maintenanceDoesNotPollActiveRows() {
        prepare();
        doAnswer(call -> { call.<Consumer<String>>getArgument(3).accept("Answer"); return null; })
                .when(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
        var queued = new AtomicReference<Runnable>();
        when(persistence.control(pair.assistantMessageId())).thenThrow(new IllegalStateException("database unavailable"));
        try (var service = new ChatTurnService(persistence, model, limits, queued::set, streams, models)) {
            service.send(actor, session, parent, request, "Question", null);
            service.maintain();
            queued.get().run();
            verify(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(persistence, never()).control(any());
            // A fresh run is not due for lease renewal; renewal cadence, not every tick, touches its row.
            verify(persistence, never()).renewLeases(any(), any());
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.COMPLETED), eq("Answer"),
                    isNull(), eq("gpt-5-mini"), isNull(), isNull(), isNull(), eq(List.of()), any(), eq(ChatResearch.EMPTY), any());
        }
    }

    @Test
    void failedLeaseRenewalKeepsTheRunAndLostOwnershipInterruptsIt() throws Exception {
        prepare();
        var renewing = new ChatExecutionProperties(1, Duration.ofMinutes(30), Duration.ofMillis(50), Duration.ofSeconds(60),
                6, 1024, 32000, 10000, null, null, 10, Duration.ofSeconds(60));
        var started = new CountDownLatch(1);
        doAnswer(call -> {
            call.<Consumer<String>>getArgument(3).accept("Partial");
            started.countDown();
            call.<Mono<?>>getArgument(2).block(Duration.ofSeconds(10));
            return null;
        }).when(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(persistence.renewLeases(any(), any())).thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(Set.of());
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor();
                var service = new ChatTurnService(persistence, model, renewing, tasks::execute, streams, models)) {
            service.send(actor, session, parent, request, "Question", null);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(80);
            service.maintain();
            verify(persistence).renewLeases(eq(List.of(pair.assistantMessageId())), eq(Duration.ofMinutes(30)));
            verify(persistence, never()).finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            // The row was reconciled elsewhere: the next renewal does not return it, so the local run stops.
            service.maintain();
            verify(persistence, Mockito.timeout(5000)).finishAndRead(eq(session), eq(pair.assistantMessageId()),
                    eq(ChatMessage.Status.FAILED), eq("Partial"), eq("CHAT_INTERRUPTED"), eq("gpt-5-mini"), isNull(), isNull(), isNull(),
                    eq(List.of()), any(), eq(ChatResearch.EMPTY), any());
        }
    }

    @Test
    void terminalOutcomeRetainsClientAndCapacityUntilActualWorkDrains() {
        prepare();
        var draining = new CompletableFuture<Void>();
        doAnswer(call -> {
            call.<Consumer<String>>getArgument(3).accept("Answer");
            call.<Consumer<CompletableFuture<Void>>>getArgument(8).accept(draining);
            return null;
        }).when(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
        try (var service = new ChatTurnService(persistence, model, limits, Runnable::run, streams, models)) {
            try {
                service.send(actor, session, parent, request, "Question", null);
                service.maintain();
                verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.COMPLETED), eq("Answer"),
                        isNull(), eq("gpt-5-mini"), isNull(), isNull(), isNull(), eq(List.of()), any(), eq(ChatResearch.EMPTY), any());
                verify(lease, never()).close();
                assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(ChatException.class,
                        () -> service.send(actor, session, parent, UUID.randomUUID(), "Question", null)).code());
            } finally { draining.complete(null); }
            verify(lease).close();
            when(persistence.reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any()))
                    .thenReturn(new ChatTurnPersistence.Reservation(pair.userMessageId(), UUID.randomUUID(), true));
            service.send(actor, session, parent, UUID.randomUUID(), "Question", null);
        }
    }

    @Test
    void terminalEventWaitsForCommitAndUsesDatabaseWinner() throws Exception {
        prepare();
        doAnswer(call -> { call.<Consumer<String>>getArgument(3).accept("Partial"); return null; })
                .when(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(persistence.finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(new ChatTurnPersistence.TerminalOutcome(ChatMessage.Status.FAILED, "CHAT_INTERRUPTED"));
        try (var service = new ChatTurnService(persistence, model, limits, Runnable::run, streams, models)) {
            service.send(actor, session, parent, request, "Question", null);
            try (var reader = streams.subscribe(pair.assistantMessageId(), 0, () -> true)) {
                var pending = reader.read();
                assertFalse(pending.done());
                assertTrue(pending.events().stream().noneMatch(event -> event.type().equals("outcome")));
                service.maintain();
                var committed = reader.read();
                assertTrue(committed.done());
                assertEquals(ChatMessage.Status.FAILED, committed.events().getLast().status());
                assertEquals("CHAT_INTERRUPTED", committed.events().getLast().failureCode());
            }
        }
    }

    @Test
    void shutdownWaitsForCanceledVirtualTaskToPersistPartial() throws Exception {
        prepare();
        var started = new CountDownLatch(1);
        doAnswer(call -> {
            assertTrue(Thread.currentThread().isVirtual());
            call.<Consumer<String>>getArgument(3).accept("Partial");
            started.countDown();
            call.<Mono<?>>getArgument(2).block(Duration.ofSeconds(5));
            return null;
        }).when(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
        try (var executor = new SimpleAsyncTaskExecutor("chat-test-")) {
            executor.setVirtualThreads(true);
            try (var service = new ChatTurnService(persistence, model, limits, executor, streams, models)) {
                service.send(actor, session, parent, request, "Question", null);
                assertTrue(started.await(5, TimeUnit.SECONDS));
            }
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.FAILED), eq("Partial"),
                    eq("CHAT_INTERRUPTED"), eq("gpt-5-mini"), isNull(), isNull(), isNull(), eq(List.of()), any(), eq(ChatResearch.EMPTY), any());
        }
    }

    @Test
    void slowTerminalWriteDoesNotHoldStopMonitorOrDuplicateFinalization() throws Exception {
        prepare();
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queued = new AtomicReference<Runnable>();
        when(persistence.authorizeReply(actor, session, pair.assistantMessageId())).thenReturn(ChatMessage.Status.RUNNING);
        when(persistence.finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(_ -> {
                    writing.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return new ChatTurnPersistence.TerminalOutcome(ChatMessage.Status.COMPLETED, null);
                });
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor();
                var service = new ChatTurnService(persistence, model, limits, queued::set, streams, models)) {
            service.send(actor, session, parent, request, "Question", null);
            var execution = tasks.submit(queued.get());
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                tasks.submit(() -> service.cancel(actor, session, pair.assistantMessageId())).get(1, TimeUnit.SECONDS);
                tasks.submit(service::maintain).get(1, TimeUnit.SECONDS);
                assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(ChatException.class,
                        () -> service.send(actor, session, parent, UUID.randomUUID(), "Question", null)).code());
            } finally { release.countDown(); }
            execution.get(5, TimeUnit.SECONDS);
            verify(persistence).finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    private final McpTurnService mcp = mock(McpTurnService.class);
    private final McpTurnTools tools = mock(McpTurnTools.class);
    private final ChatExecutionProperties twoTurns = new ChatExecutionProperties(2, Duration.ofMinutes(30), Duration.ofSeconds(60),
            Duration.ofSeconds(60), 6, 1024, 32000, 10000, null, null, 10, Duration.ofSeconds(60));

    private ChatTurnService withMcp(ChatExecutionProperties properties, TaskExecutor executor) {
        return new ChatTurnService(persistence, model, properties, executor, streams, models, null, null, null, null, mcp);
    }

    private ChatCommand mcpCommand(UUID requestId) {
        return new ChatCommand(ChatCommand.Operation.SEND, parent, requestId, "Question", null, List.of(), WebSearchMode.off,
                ImageMode.off, List.of(UUID.randomUUID()));
    }

    /** Blocks {@code mcp.open} until released, as a slow MCP server or OAuth token endpoint would. */
    private void slowMcpOpen(CountDownLatch opening, CountDownLatch release) {
        when(mcp.open(any(), any())).thenAnswer(_ -> {
            opening.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return tools;
        });
    }

    private UUID sameStripeAs(UUID session) {
        UUID other;
        do { other = UUID.randomUUID(); }
        while (Math.floorMod(other.hashCode(), 128) != Math.floorMod(session.hashCode(), 128));
        return other;
    }

    @Test
    void slowMcpOpenDoesNotBlockSendStopOrSubscribeForAnotherSessionOnTheSameStripe() throws Exception {
        prepare();
        var other = sameStripeAs(session);
        var otherPair = new ChatTurnPersistence.Reservation(UUID.randomUUID(), UUID.randomUUID(), true);
        when(persistence.reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any()))
                .thenAnswer(call -> session.equals(call.getArgument(1)) ? pair : otherPair);
        when(persistence.authorizeReply(actor, other, otherPair.assistantMessageId())).thenReturn(ChatMessage.Status.RUNNING);
        var opening = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        slowMcpOpen(opening, release);
        var queued = new ConcurrentLinkedQueue<Runnable>();
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor(); var service = withMcp(twoTurns, queued::add)) {
            try {
                var sending = tasks.submit(() -> service.command(actor, session, mcpCommand(request)));
                assertTrue(opening.await(5, TimeUnit.SECONDS));
                var accepted = tasks.submit(() -> service.send(actor, other, parent, UUID.randomUUID(), "Question", null))
                        .get(1, TimeUnit.SECONDS);
                assertEquals(otherPair.assistantMessageId(), accepted.assistantMessageId());
                assertEquals(ChatMessage.Status.RUNNING, tasks.submit(() -> service.cancel(actor, other, otherPair.assistantMessageId()))
                        .get(1, TimeUnit.SECONDS).status());
                tasks.submit(() -> {
                    try (var reader = service.subscribe(actor, other, otherPair.assistantMessageId(), 0).get()) {
                        assertFalse(reader.read().done());
                    }
                    return null;
                }).get(1, TimeUnit.SECONDS);
                assertFalse(sending.isDone());
                release.countDown();
                assertEquals(pair.assistantMessageId(), sending.get(5, TimeUnit.SECONDS).assistantMessageId());
            } finally {
                release.countDown();
                for (Runnable run; (run = queued.poll()) != null; ) run.run();
            }
        }
    }

    @Test
    void replayedRequestDuringSlowMcpOpenReturnsTheReservedTurnWithoutReservingAgain() throws Exception {
        prepare();
        // The mock models the database: once reserved, the request id resolves to that reservation.
        var stored = new AtomicReference<ChatTurnPersistence.Reservation>();
        when(persistence.existing(any(), any(), any(ChatCommand.class))).thenAnswer(_ -> Optional.ofNullable(stored.get())
                .map(saved -> new ChatTurnPersistence.Reservation(saved.userMessageId(), saved.assistantMessageId(), false)));
        when(persistence.reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any())).thenAnswer(_ -> {
            stored.set(pair);
            return pair;
        });
        var opening = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        slowMcpOpen(opening, release);
        var queued = new ConcurrentLinkedQueue<Runnable>();
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor(); var service = withMcp(limits, queued::add)) {
            try {
                var first = tasks.submit(() -> service.command(actor, session, mcpCommand(request)));
                assertTrue(opening.await(5, TimeUnit.SECONDS));
                var second = tasks.submit(() -> service.command(actor, session, mcpCommand(request))).get(1, TimeUnit.SECONDS);
                release.countDown();
                assertEquals(pair.assistantMessageId(), second.assistantMessageId());
                assertEquals(pair.assistantMessageId(), first.get(5, TimeUnit.SECONDS).assistantMessageId());
                verify(persistence).reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any());
                verify(mcp).open(any(), any());
                assertEquals(1, queued.size());
            } finally {
                release.countDown();
                for (Runnable run; (run = queued.poll()) != null; ) run.run();
            }
        }
    }

    @Test
    void stopWhileMcpOpensCancelsTheTurnWithoutCallingTheModel() throws Exception {
        prepare();
        when(persistence.authorizeReply(actor, session, pair.assistantMessageId())).thenReturn(ChatMessage.Status.RUNNING);
        var opening = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        slowMcpOpen(opening, release);
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor(); var service = withMcp(limits, Runnable::run)) {
            try {
                var sending = tasks.submit(() -> service.command(actor, session, mcpCommand(request)));
                assertTrue(opening.await(5, TimeUnit.SECONDS));
                assertEquals(ChatMessage.Status.RUNNING, tasks.submit(() -> service.cancel(actor, session, pair.assistantMessageId()))
                        .get(1, TimeUnit.SECONDS).status());
                release.countDown();
                assertEquals(pair.assistantMessageId(), sending.get(5, TimeUnit.SECONDS).assistantMessageId());
            } finally { release.countDown(); }
            verify(model, never()).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.CANCELED), eq(""),
                    isNull(), eq("gpt-5-mini"), isNull(), isNull(), isNull(), eq(List.of()), any(), eq(ChatResearch.EMPTY), any());
            // The opened sessions belong to the run, which closes them with its model lease.
            verify(tools).close();
            verify(lease).close();
            // The permit returned: a new turn is admitted.
            when(persistence.reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any()))
                    .thenReturn(new ChatTurnPersistence.Reservation(pair.userMessageId(), UUID.randomUUID(), true));
            service.send(actor, session, parent, UUID.randomUUID(), "Question", null);
        }
    }

    @Test
    void mcpOpenFailureAfterReservationFailsTheReservedTurn() throws Exception {
        prepare();
        when(mcp.open(any(), any())).thenThrow(new IllegalStateException("authorization server unavailable"));
        try (var service = withMcp(limits, Runnable::run)) {
            assertThrows(IllegalStateException.class, () -> service.command(actor, session, mcpCommand(request)));
            verify(persistence).reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any());
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.FAILED), eq(""),
                    eq("CHAT_SETUP_FAILED"), eq("gpt-5-mini"), isNull(), isNull(), isNull(), eq(List.of()), any(), eq(ChatResearch.EMPTY), any());
            verify(model, never()).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(lease).close();
            // A subscriber sees the failed outcome, and the permit returned so a new turn is admitted.
            try (var reader = streams.subscribe(pair.assistantMessageId(), 0, () -> true)) {
                var outcome = reader.read();
                assertTrue(outcome.done());
                assertEquals("CHAT_SETUP_FAILED", outcome.events().getLast().failureCode());
            }
            when(persistence.reserve(any(), any(), any(ChatCommand.class), any(), anyInt(), any()))
                    .thenReturn(new ChatTurnPersistence.Reservation(pair.userMessageId(), UUID.randomUUID(), true));
            service.send(actor, session, parent, UUID.randomUUID(), "Question", null);
        }
    }

    /** The failure codes a turn persists and the web shows; everything else is reported as CHAT_EXECUTION_FAILED. */
    static Stream<Arguments> turnFailures() {
        return Stream.of(
                Arguments.of(turnFailure("CHAT_OUTPUT_LIMIT"), "CHAT_OUTPUT_LIMIT"),
                Arguments.of(turnFailure("CHAT_CYCLE_LIMIT"), "CHAT_CYCLE_LIMIT"),
                Arguments.of(turnFailure("CHAT_BUDGET_EXCEEDED"), "CHAT_BUDGET_EXCEEDED"),
                Arguments.of(turnFailure("CHAT_MODEL_UNAVAILABLE"), "CHAT_MODEL_UNAVAILABLE"),
                Arguments.of(turnFailure("CHAT_INCOMPLETE_RESPONSE"), "CHAT_INCOMPLETE_RESPONSE"),
                Arguments.of(turnFailure("CHAT_LAST_CYCLE_TOOL_CALL"), "CHAT_LAST_CYCLE_TOOL_CALL"),
                Arguments.of(turnFailure("CHAT_UNSUPPORTED_OPTIONS"), "CHAT_UNSUPPORTED_OPTIONS"),
                Arguments.of(turnFailure("CHAT_EMPTY_RESPONSE"), "CHAT_EMPTY_RESPONSE"),
                Arguments.of(turnFailure("CHAT_CONTEXT_LIMIT"), "CHAT_CONTEXT_LIMIT"),
                Arguments.of(turnFailure("CHAT_MODEL_OUTPUT_LIMIT"), "CHAT_MODEL_OUTPUT_LIMIT"),
                // Raised as turn failures but reported as a generic failure.
                Arguments.of(turnFailure("CHAT_DEADLINE"), "CHAT_EXECUTION_FAILED"),
                Arguments.of(turnFailure("CHAT_PROVIDER_UNAVAILABLE"), "CHAT_EXECUTION_FAILED"),
                // A wrapped failure is found through its causes; a hidden one does not stop the search.
                Arguments.of(new RuntimeException("wrapper", turnFailure("CHAT_OUTPUT_LIMIT")), "CHAT_OUTPUT_LIMIT"),
                Arguments.of(new RuntimeException("wrapper", withCause(turnFailure("CHAT_DEADLINE"), turnFailure("CHAT_BUDGET_EXCEEDED"))),
                        "CHAT_BUDGET_EXCEEDED"),
                // Business failures and arbitrary provider messages never become the persisted code.
                Arguments.of(ChatException.researchUnavailable(), "CHAT_EXECUTION_FAILED"),
                Arguments.of(new RuntimeException("provider said something private"), "CHAT_EXECUTION_FAILED"),
                // A code carried only as a message is not a turn failure.
                Arguments.of(new IllegalStateException("CHAT_OUTPUT_LIMIT"), "CHAT_EXECUTION_FAILED"));
    }

    private static RuntimeException turnFailure(String code) {
        return Arrays.stream(TurnFailure.values()).filter(failure -> failure.code().equals(code))
                .findFirst().orElseThrow().exception();
    }

    private static RuntimeException withCause(RuntimeException failure, Throwable cause) {
        failure.initCause(cause);
        return failure;
    }

    @ParameterizedTest
    @MethodSource("turnFailures")
    void failedTurnPersistsOnlyReportedFailureCodes(RuntimeException failure, String persisted) {
        prepare();
        doThrow(failure).when(model).execute(any(), any(), any(), any(), any(), any(), any(), any(), any());
        try (var service = new ChatTurnService(persistence, model, limits, Runnable::run, streams, models)) {
            service.send(actor, session, parent, request, "Question", null);
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.FAILED), eq(""),
                    eq(persisted), eq("gpt-5-mini"), isNull(), isNull(), isNull(), eq(List.of()), any(), eq(ChatResearch.EMPTY), any());
        }
    }

    @Test
    void emptyAnswerFailsWithEmptyResponse() {
        prepare();
        try (var service = new ChatTurnService(persistence, model, limits, Runnable::run, streams, models)) {
            service.send(actor, session, parent, request, "Question", null);
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.FAILED), eq(""),
                    eq("CHAT_EMPTY_RESPONSE"), eq("gpt-5-mini"), isNull(), isNull(), isNull(), eq(List.of()), any(), eq(ChatResearch.EMPTY), any());
        }
    }
}
