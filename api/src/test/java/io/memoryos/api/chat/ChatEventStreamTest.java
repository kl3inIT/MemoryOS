package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.chat.ChatMessage.Status;
import io.memoryos.chat.ChatResearchEvent;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.chat.ChatSource;
import java.util.List;
import io.memoryos.chat.streaming.ChatStreamProperties;
import io.memoryos.chat.streaming.StreamBufferWriter;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NullMarked;
import org.reactivestreams.Subscription;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.scheduler.Schedulers;

@NullMarked
class ChatEventStreamTest {
    private final UUID assistant = UUID.randomUUID();
    private final StreamBufferWriter streams = new StreamBufferWriter(new ChatStreamProperties(
            4096, 16384, Duration.ofMinutes(60), Duration.ofMinutes(10), 512, Duration.ofMillis(25), 2048,
            4, 8, 2048, 16, Duration.ofSeconds(15), Duration.ofMinutes(1)));

    @Test
    void searchEvidenceReplaysBeforeTextAndTerminalWithStableWireIdentity() {
        var source = new ChatSource(1, UUID.randomUUID(), UUID.randomUUID(), "HR", 2, 2,
                List.of(new ChatSource.Provenance(2, "[]")));
        streams.open(assistant);
        streams.tool(assistant, new ChatToolEvent(new ChatToolEvent.Call("tool-1", "searchKnowledge"), source));
        streams.append(assistant, "Twelve days [1]");
        streams.finish(assistant, Status.CANCELED, null);
        var events = ChatEventStream.encode(() -> streams.subscribe(assistant, 0), assistant, Schedulers.immediate(), Duration.ofSeconds(2))
                .collectList().block(Duration.ofSeconds(2));
        assertNotNull(events);
        assertEquals(List.of("tool", "text-delta", "outcome"), events.stream().map(ServerSentEvent::event).toList());
        var payload = assertInstanceOf(ChatEventStream.ToolEvent.class, events.getFirst().data());
        assertEquals("tool-1", payload.toolCallId());
        assertEquals("searchKnowledge", payload.toolName());
        assertNotNull(payload.source());
        assertEquals(source.documentId(), payload.source().documentId());
        assertEquals(assistant + ":1", events.getFirst().id());
        assertEquals(0, streams.readerCount());
    }

    @Test
    void reasoningAndToolStagesReplayInSequenceBeforeTextAndTerminal() {
        var call = new ChatToolEvent.Call("call_1", "web_search");
        streams.open(assistant);
        streams.reasoning(assistant, "Checking ");
        streams.reasoning(assistant, "sources");
        streams.tool(assistant, new ChatToolEvent(call, ChatToolEvent.Stage.STARTED));
        streams.tool(assistant, ChatToolEvent.finished(call, false, 42L));
        streams.append(assistant, "Answer");
        streams.finish(assistant, Status.COMPLETED, null);
        var events = ChatEventStream.encode(() -> streams.subscribe(assistant, 0), assistant, Schedulers.immediate(), Duration.ofSeconds(2))
                .collectList().block(Duration.ofSeconds(2));
        assertNotNull(events);
        assertEquals(List.of("reasoning", "reasoning", "tool", "tool", "text-delta", "outcome"), events.stream().map(ServerSentEvent::event).toList());
        assertEquals("Checking sources", events.subList(0, 2).stream()
                .map(event -> assertInstanceOf(ChatEventStream.ReasoningEvent.class, event.data()).text()).reduce("", String::concat));
        var done = assertInstanceOf(ChatEventStream.ToolEvent.class, events.get(3).data());
        assertEquals(ChatToolEvent.Stage.COMPLETED, done.stage());
        assertEquals("web_search", done.toolName());
        assertEquals(42L, done.durationMs());
        assertEquals(assistant + ":4", events.get(3).id());
    }

    @Test
    void researchEventsAndNestedStepsKeepTheirPlacementOnTheWire() {
        var agent = new ChatToolEvent.Call("call_agent", "research_agent");
        streams.open(assistant);
        streams.research(assistant, ChatResearchEvent.plan("1. Revenue"));
        streams.research(assistant, ChatResearchEvent.branching(2));
        streams.tool(assistant, new ChatToolEvent(agent, ChatToolEvent.Stage.STARTED).tab(1));
        streams.research(assistant, ChatResearchEvent.agent("call_agent", 1, "Revenue in 2025"));
        streams.tool(assistant, new ChatToolEvent(new ChatToolEvent.Call("call_search", "searchKnowledge"), ChatToolEvent.Stage.STARTED).nested("call_agent"));
        streams.reasoning(assistant, "Next, costs", "call_agent");
        streams.research(assistant, ChatResearchEvent.report("call_agent", "Revenue grew [1]."));
        streams.research(assistant, ChatResearchEvent.citations("call_agent", List.of(new ChatResearchEvent.Citation(1, 2))));
        streams.finish(assistant, Status.COMPLETED, null);
        var events = ChatEventStream.encode(() -> streams.subscribe(assistant, 0), assistant, Schedulers.immediate(), Duration.ofSeconds(2))
                .collectList().block(Duration.ofSeconds(2));
        assertNotNull(events);
        assertEquals(List.of("research-plan", "top-level-branching", "tool", "research-agent-start", "tool", "reasoning",
                "intermediate-report", "intermediate-report-citations", "outcome"), events.stream().map(ServerSentEvent::event).toList());
        assertEquals("1. Revenue", assertInstanceOf(ChatEventStream.ResearchPlanEvent.class, events.get(0).data()).text());
        assertEquals(2, assertInstanceOf(ChatEventStream.TopLevelBranchingEvent.class, events.get(1).data()).branches());
        var agentStep = assertInstanceOf(ChatEventStream.ToolEvent.class, events.get(2).data());
        assertEquals(1, agentStep.tabIndex());
        assertNull(agentStep.parentToolCallId());
        var start = assertInstanceOf(ChatEventStream.ResearchAgentStartEvent.class, events.get(3).data());
        assertEquals("Revenue in 2025", start.task());
        assertEquals(1, start.tabIndex());
        var nested = assertInstanceOf(ChatEventStream.ToolEvent.class, events.get(4).data());
        assertEquals("call_agent", nested.parentToolCallId());
        assertNull(nested.tabIndex());
        assertEquals("call_agent", assertInstanceOf(ChatEventStream.ReasoningEvent.class, events.get(5).data()).parentToolCallId());
        var report = assertInstanceOf(ChatEventStream.IntermediateReportEvent.class, events.get(6).data());
        assertEquals("call_agent", report.toolCallId());
        assertEquals("Revenue grew [1].", report.text());
        assertEquals(List.of(new ChatEventStream.ResearchCitation(1, 2)),
                assertInstanceOf(ChatEventStream.IntermediateReportCitationsEvent.class, events.get(7).data()).citations());
        assertEquals(assistant + ":7", events.get(6).id());
    }

    @Test
    void replayKeepsSequenceAndTerminalAndReleasesReader() {
        streams.open(assistant);
        streams.append(assistant, "First 😀");
        streams.flush();
        streams.append(assistant, " second");
        streams.finish(assistant, Status.COMPLETED, null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduler = Schedulers.fromExecutor(executor);
            var events = ChatEventStream.encode(() -> streams.subscribe(assistant, 1), assistant, scheduler,
                    Duration.ofSeconds(5)).collectList().block(Duration.ofSeconds(5));
            assertNotNull(events);
            assertEquals(2, events.size());
            assertEquals(assistant + ":2", events.getFirst().id());
            assertEquals("outcome", events.getLast().event());
            assertEquals(0, streams.readerCount());
            scheduler.dispose();
        }
    }

    @Test
    void unconsumedPublisherHoldsNoReaderAndEachSubscriptionOwnsItsReader() {
        streams.open(assistant);
        streams.append(assistant, "Answer");
        streams.finish(assistant, Status.COMPLETED, null);
        var events = ChatEventStream.encode(() -> streams.subscribe(assistant, 0), assistant,
                Schedulers.immediate(), Duration.ofSeconds(5));
        assertEquals(0, streams.readerCount());
        var first = events.collectList().block(Duration.ofSeconds(5));
        var second = events.collectList().block(Duration.ofSeconds(5));
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.size(), second.size());
        assertEquals("outcome", second.getLast().event());
        assertEquals(0, streams.readerCount());
    }

    @Test
    void disconnectReleasesWaitingReaderAndWriterContinuesForReconnect() throws Exception {
        streams.open(assistant);
        streams.append(assistant, "Partial");
        streams.flush();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduler = Schedulers.fromExecutor(executor);
            var received = new CountDownLatch(1);
            var subscription = ChatEventStream.encode(() -> streams.subscribe(assistant, 0), assistant, scheduler,
                    Duration.ofSeconds(5)).subscribe(_ -> {
                        assertTrue(Thread.currentThread().isVirtual());
                        received.countDown();
                    });
            assertTrue(received.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            assertEquals(0, streams.readerCount());
            streams.append(assistant, " continues");
            streams.finish(assistant, Status.COMPLETED, null);
            var replay = ChatEventStream.encode(() -> streams.subscribe(assistant, 1), assistant, scheduler,
                    Duration.ofSeconds(5)).collectList().block(Duration.ofSeconds(5));
            assertNotNull(replay);
            assertEquals(" continues", assertInstanceOf(ChatEventStream.TextDeltaEvent.class, replay.getFirst().data()).text());
            assertEquals("outcome", replay.getLast().event());
            assertEquals(0, streams.readerCount());
            scheduler.dispose();
        }
    }

    @Test
    void readerWithNoDemandIsBoundedAndReceivesResetWhenItFallsBehind() throws Exception {
        streams.open(assistant);
        streams.append(assistant, "First");
        streams.flush();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduler = Schedulers.fromExecutor(executor);
            var first = new CountDownLatch(1);
            var done = new CountDownLatch(1);
            var frames = new CopyOnWriteArrayList<ServerSentEvent<Object>>();
            var subscriber = new BaseSubscriber<ServerSentEvent<Object>>() {
                @Override protected void hookOnSubscribe(Subscription subscription) { request(1); }
                @Override protected void hookOnNext(ServerSentEvent<Object> event) { frames.add(event); first.countDown(); }
                @Override protected void hookOnComplete() { done.countDown(); }
            };
            ChatEventStream.encode(() -> streams.subscribe(assistant, 0), assistant, scheduler,
                    Duration.ofSeconds(5)).subscribe(subscriber);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            streams.append(assistant, "x".repeat(16000));
            streams.finish(assistant, Status.COMPLETED, null);
            assertEquals(1, frames.size());
            subscriber.requestUnbounded();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals("reset", frames.getLast().event());
            assertEquals(0, streams.readerCount());
            scheduler.dispose();
        }
    }

    @Test
    void connectionTimeoutOnlyClosesReaderAndMissingBufferResetsWithoutEventId() {
        streams.open(assistant);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduler = Schedulers.fromExecutor(executor);
            var frames = ChatEventStream.encode(() -> streams.subscribe(assistant, 0), assistant, scheduler,
                    Duration.ofMillis(50)).collectList().block(Duration.ofSeconds(5));
            assertNotNull(frames);
            assertTrue(frames.isEmpty());
            assertEquals(0, streams.readerCount());
            streams.append(assistant, "Still running");
            streams.finish(assistant, Status.CANCELED, null);
            var missing = ChatEventStream.encode(() -> streams.subscribe(UUID.randomUUID(), 0), assistant, scheduler,
                    Duration.ofSeconds(5)).collectList().block(Duration.ofSeconds(5));
            assertNotNull(missing);
            assertEquals(1, missing.size());
            assertEquals("reset", missing.getFirst().event());
            assertNull(missing.getFirst().id());
            scheduler.dispose();
        }
    }
}
