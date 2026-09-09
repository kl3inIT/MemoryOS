package io.memoryos.chat;

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

import io.memoryos.chat.application.ChatTurnPersistence;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.chat.execution.ChatModelBinding;
import io.memoryos.chat.catalog.ChatModelResolver;
import io.memoryos.chat.catalog.ChatModelClients;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.springframework.ai.chat.model.ChatModel;
import io.memoryos.chat.streaming.ChatStreamProperties;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import reactor.core.publisher.Mono;

class ChatTurnServiceTest {
    private final ChatTurnPersistence persistence = mock(ChatTurnPersistence.class);
    private final ChatModelExecutor model = mock(ChatModelExecutor.class);
    private final ChatModelResolver models = mock(ChatModelResolver.class);
    private final ChatModelClients.Lease lease = mock(ChatModelClients.Lease.class);
    private final ChatExecutionProperties limits = new ChatExecutionProperties(1, Duration.ofMinutes(1), 6, 1024, 32000, 10000,
            Integer.MAX_VALUE, Double.MAX_VALUE);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final StreamBufferWriter streams = new StreamBufferWriter(new ChatStreamProperties(4096, 16384,
            Duration.ofMinutes(1), 512, Duration.ofMillis(25), 2048, 4, 8, 2048, 16,
            Duration.ofMillis(5), Duration.ofMinutes(1)));
    private final UUID session = UUID.randomUUID();
    private final UUID parent = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();
    private final ChatTurnPersistence.Reservation pair = new ChatTurnPersistence.Reservation(UUID.randomUUID(), UUID.randomUUID(), true);

    private void prepare() {
        var binding = new ChatModelBinding(new SpringAiLlmService("gpt-5-mini", "fixture", mock(ChatModel.class)), p -> p);
        when(lease.binding()).thenReturn(binding);
        when(models.resolve(any(), any(), any())).thenReturn(new ChatModelResolver.Resolved(UUID.randomUUID(), null, lease));
        when(persistence.finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> new ChatTurnPersistence.TerminalOutcome(call.getArgument(2), call.getArgument(4)));
        when(persistence.existing(any(), any(), any(), any(), anyString(), any())).thenReturn(Optional.empty());
        when(persistence.reserve(any(), any(), any(), any(), anyString(), any(), anyInt(), any())).thenReturn(pair);
        var question = new ChatMessage(pair.userMessageId(), session, parent, pair.assistantMessageId(), ChatMessage.Role.USER,
                "Question", ChatMessage.Status.COMPLETED, Instant.now(), Instant.now());
        when(persistence.loadContext(any(), any(), any())).thenReturn(new ChatTurnPersistence.TurnContext(actor,
                new TenantId(UUID.randomUUID()), "gpt-5-mini", "Answer", List.of(question), Instant.now().plusSeconds(60)));
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
            verify(model, never()).execute(any(), any(), any(), any(), any());
            verify(persistence).reserve(any(), any(), any(), any(), anyString(), any(), anyInt(), any());
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.CANCELED), eq(""),
                    isNull(), eq("gpt-5-mini"), isNull(), isNull(), isNull());
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
                    eq("CHAT_SUBMIT_FAILED"), eq("gpt-5-mini"), isNull(), isNull(), isNull());
            verify(model, never()).execute(any(), any(), any(), any(), any());
            // A second rejected dispatch reaches the executor; it is not falsely rejected as capacity exhausted.
            when(persistence.reserve(any(), any(), any(), any(), anyString(), any(), anyInt(), any()))
                    .thenReturn(new ChatTurnPersistence.Reservation(pair.userMessageId(), UUID.randomUUID(), true));
            assertThrows(TaskRejectedException.class, () -> service.send(actor, session, parent, UUID.randomUUID(), "Question", null));
        }
    }

    @Test
    @SuppressWarnings("resource") // This Mockito stubbing does not acquire a real lease.
    void unavailableProviderRejectsBeforeReservation() {
        prepare();
        doThrow(ChatException.providerUnavailable()).when(models).resolve(any(), any(), any());
        try (var service = new ChatTurnService(persistence, model, limits, Runnable::run, streams, models)) {
            assertEquals("CHAT_PROVIDER_UNAVAILABLE", assertThrows(ChatException.class,
                    () -> service.send(actor, session, parent, request, "Question", null)).code());
            verify(persistence, never()).reserve(any(), any(), any(), any(), anyString(), any(), anyInt(), any());
        }
    }

    @Test
    void maintenanceDoesNotPollActiveRows() {
        prepare();
        doAnswer(call -> { call.<Consumer<String>>getArgument(3).accept("Answer"); return null; })
                .when(model).execute(any(), any(), any(), any(), any());
        var queued = new AtomicReference<Runnable>();
        when(persistence.control(pair.assistantMessageId())).thenThrow(new IllegalStateException("database unavailable"));
        try (var service = new ChatTurnService(persistence, model, limits, queued::set, streams, models)) {
            service.send(actor, session, parent, request, "Question", null);
            service.maintain();
            queued.get().run();
            verify(model).execute(any(), any(), any(), any(), any());
            verify(persistence, never()).control(any());
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.COMPLETED), eq("Answer"),
                    isNull(), eq("gpt-5-mini"), isNull(), isNull(), isNull());
        }
    }

    @Test
    void terminalEventWaitsForCommitAndUsesDatabaseWinner() throws Exception {
        prepare();
        doAnswer(call -> { call.<Consumer<String>>getArgument(3).accept("Partial"); return null; })
                .when(model).execute(any(), any(), any(), any(), any());
        when(persistence.finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(new ChatTurnPersistence.TerminalOutcome(ChatMessage.Status.FAILED, "CHAT_INTERRUPTED"));
        try (var service = new ChatTurnService(persistence, model, limits, Runnable::run, streams, models)) {
            service.send(actor, session, parent, request, "Question", null);
            try (var reader = streams.subscribe(pair.assistantMessageId(), 0)) {
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
        }).when(model).execute(any(), any(), any(), any(), any());
        try (var executor = new SimpleAsyncTaskExecutor("chat-test-")) {
            executor.setVirtualThreads(true);
            try (var service = new ChatTurnService(persistence, model, limits, executor, streams, models)) {
                service.send(actor, session, parent, request, "Question", null);
                assertTrue(started.await(5, TimeUnit.SECONDS));
            }
            verify(persistence).finishAndRead(eq(session), eq(pair.assistantMessageId()), eq(ChatMessage.Status.FAILED), eq("Partial"),
                    eq("CHAT_INTERRUPTED"), eq("gpt-5-mini"), isNull(), isNull(), isNull());
        }
    }

    @Test
    void slowTerminalWriteDoesNotHoldStopMonitorOrDuplicateFinalization() throws Exception {
        prepare();
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queued = new AtomicReference<Runnable>();
        when(persistence.authorizeReply(actor, session, pair.assistantMessageId())).thenReturn(ChatMessage.Status.RUNNING);
        when(persistence.finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any()))
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
            verify(persistence).finishAndRead(any(), any(), any(), anyString(), any(), any(), any(), any(), any());
        }
    }
}
