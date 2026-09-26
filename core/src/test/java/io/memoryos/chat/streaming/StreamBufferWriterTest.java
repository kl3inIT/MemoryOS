package io.memoryos.chat.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.chat.ChatCodeEvent;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatImageEvent;
import io.memoryos.chat.ChatMessage.Status;
import io.memoryos.chat.ChatResearchEvent;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.retrieval.SearchFilters;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

class StreamBufferWriterTest {
    private final StringRedisTemplate redis = TestRedis.template();
    private final ChatStreamProperties limits = limits(65536);

    private static ChatStreamProperties limits(int runBytes) {
        return new ChatStreamProperties(runBytes, Duration.ofMinutes(60), Duration.ofMinutes(10), 32, Duration.ofMillis(25),
                2, 4, 65536, Duration.ofMillis(5), Duration.ofMillis(50), Duration.ofMinutes(1));
    }

    @Test
    void replayThenLiveRetainsIdentityAndResumesFromTheCursor() throws Exception {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        writer.open(id);
        writer.append(id, "first😀");
        writer.flush();
        try (var reader = writer.subscribe(id, 0, () -> true)) {
            writer.append(id, "second");
            writer.finish(id, Status.COMPLETED, null);
            var events = readAll(reader);
            assertEquals("first😀second", events.stream().map(StreamBufferWriter.Event::text).filter(Objects::nonNull).reduce("", String::concat));
            assertEquals(List.of(1L, 2L, 3L), events.stream().map(StreamBufferWriter.Event::sequence).toList());
            assertEquals(Status.COMPLETED, events.getLast().status());
        }
        // Any process reads the same reply: this reader only knows Redis.
        var other = new StreamBufferWriter(redis, limits);
        try (var reader = other.subscribe(id, 1, () -> false)) {
            assertEquals(List.of(2L, 3L), readAll(reader).stream().map(StreamBufferWriter.Event::sequence).toList());
        }
        assertEquals(0, writer.readerCount());
        assertEquals(0, other.readerCount());
    }

    @Test
    void aLiveReaderInTheWritingProcessWakesOnTheWriteInsteadOfThePollInterval() throws Exception {
        // As Onyx's attached response reads its in-memory tee: a 5-second poll must not delay a live token.
        var slowPoll = new ChatStreamProperties(65536, Duration.ofMinutes(60), Duration.ofMinutes(10), 32, Duration.ofMillis(25),
                2, 4, 65536, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(1));
        var writer = new StreamBufferWriter(redis, slowPoll);
        var id = UUID.randomUUID();
        writer.open(id);
        writer.append(id, "first");
        writer.flush();
        try (var reader = writer.subscribe(id, 1, () -> true)) {
            var started = System.nanoTime();
            var pending = CompletableFuture.supplyAsync(() -> {
                try { return reader.read(); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            });
            Thread.sleep(150);
            writer.append(id, "second");
            writer.flush();
            var batch = pending.get(3, TimeUnit.SECONDS);
            assertEquals("second", batch.events().getFirst().text());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000);
        }
    }

    @Test
    void reasoningChunksSeparatelyFromTextAndKeepsPublicationOrder() throws Exception {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        writer.open(id);
        writer.reasoning(id, "Think");
        writer.reasoning(id, "ing");
        writer.append(id, "Answer");
        writer.tool(id, new ChatToolEvent(new ChatToolEvent.Call("call_1", "read_file"), ChatToolEvent.Stage.STARTED));
        writer.finish(id, Status.COMPLETED, null);
        List<StreamBufferWriter.Event> events;
        try (var reader = writer.subscribe(id, 0, () -> false)) {
            events = readAll(reader);
        }
        // The first chunk flushes immediately, as for answer text; a type change flushes the pending chunk.
        assertEquals(List.of("reasoning", "reasoning", "text-delta", "tool", "outcome"), events.stream().map(StreamBufferWriter.Event::type).toList());
        assertEquals("Thinking", events.stream().filter(event -> event.type().equals("reasoning")).map(StreamBufferWriter.Event::text).reduce("", String::concat));
        assertEquals("Answer", events.get(2).text());
    }

    @Test
    void researchDeltasChunkPerAgentAndOtherResearchEventsFlushInOrder() throws Exception {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        writer.open(id);
        writer.research(id, ChatResearchEvent.plan("1. Rev"));
        writer.research(id, ChatResearchEvent.plan("enue"));
        writer.research(id, ChatResearchEvent.branching(2));
        writer.research(id, ChatResearchEvent.agent("call_a", 0, "Revenue"));
        writer.research(id, ChatResearchEvent.agent("call_b", 1, "Costs"));
        writer.research(id, ChatResearchEvent.report("call_a", "Grew "));
        writer.research(id, ChatResearchEvent.report("call_b", "Fell "));
        writer.research(id, ChatResearchEvent.report("call_a", "[1]."));
        writer.reasoning(id, "Agent b thinks", "call_b");
        writer.reasoning(id, "Orchestrator thinks");
        writer.research(id, ChatResearchEvent.citations("call_a", List.of(new ChatResearchEvent.Citation(1, 3))));
        writer.finish(id, Status.COMPLETED, null);
        List<StreamBufferWriter.Event> events;
        try (var reader = writer.subscribe(id, 0, () -> false)) {
            events = readAll(reader);
        }
        assertEquals(List.of("research-plan", "research-plan", "top-level-branching", "research-agent-start", "research-agent-start",
                        "intermediate-report", "intermediate-report", "intermediate-report", "reasoning", "reasoning", "intermediate-report-citations", "outcome"),
                events.stream().map(StreamBufferWriter.Event::type).toList());
        assertEquals("1. Revenue", events.subList(0, 2).stream().map(event -> Objects.requireNonNull(event.research()).text()).reduce("", String::concat));
        // A pending delta belongs to one agent: another agent's delta flushes it first.
        assertEquals(List.of("call_a", "call_b", "call_a"), events.subList(5, 8).stream()
                .map(event -> Objects.requireNonNull(event.research()).toolCallId()).toList());
        assertEquals("call_b", events.get(8).parentToolCallId());
        assertNull(events.get(9).parentToolCallId());
        assertEquals(3, Objects.requireNonNull(events.get(10).research()).citations().getFirst().citationId());
        for (int i = 0; i < events.size(); i++) assertEquals(i + 1, events.get(i).sequence());
    }

    @Test
    void everyEventPayloadSurvivesRedisUnchanged() throws Exception {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        var call = new ChatToolEvent.Call("call_s", "search_knowledge");
        var source = new ChatSource(1, UUID.randomUUID(), UUID.randomUUID(), "HR", 2, 2, List.of(new ChatSource.Provenance(2, "[]")));
        var published = List.of(
                new ChatToolEvent(call, new ChatToolEvent.QueryPlan(List.of("leave policy"), SearchFilters.NONE)),
                ChatToolEvent.reading(call, List.of(new ChatToolEvent.ReadingDocument(UUID.randomUUID(), UUID.randomUUID(), "HR", 0, 3))),
                new ChatToolEvent(call, source).nested("call_agent"),
                ChatToolEvent.finished(call, false, 12L).tab(1));
        var image = new ChatImageEvent("call_i", ChatImageEvent.Stage.COMPLETED, UUID.randomUUID(), "image/png", "A cat");
        writer.open(id);
        published.forEach(event -> writer.tool(id, event));
        writer.image(id, image);
        var code = List.of(ChatCodeEvent.running("call_p", "print(1)"), ChatCodeEvent.output("call_p", "stdout", "1\n"),
                ChatCodeEvent.output("call_p", "stderr", "warning\n"),
                ChatCodeEvent.completed("call_p", List.of(new ChatCodeEvent.GeneratedFile(UUID.randomUUID(), "a.csv", "text/csv", 3))));
        code.forEach(event -> writer.code(id, event));
        writer.finish(id, Status.FAILED, "CHAT_INTERRUPTED", true);
        List<StreamBufferWriter.Event> events;
        try (var reader = writer.subscribe(id, 0, () -> false)) {
            events = readAll(reader);
        }
        assertEquals(published, events.subList(0, 4).stream().map(StreamBufferWriter.Event::tool).toList());
        assertEquals(image, events.get(4).image());
        assertEquals(code, events.subList(5, 9).stream().map(StreamBufferWriter.Event::code).toList());
        assertEquals(new StreamBufferWriter.Event(id, 10, "outcome", null, Status.FAILED, "CHAT_INTERRUPTED", null, true), events.get(9));
    }

    @Test
    void entriesUseSequencesAsIdsAndCarryOnyxRetention() throws Exception {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        String key = StreamBufferWriter.key(id);
        writer.open(id);
        writer.append(id, "text");
        writer.flush();
        assertEquals(List.of(RecordId.of(0, 1)), redis.opsForStream().range(key, Range.unbounded())
                .stream().map(record -> record.getId()).toList());
        // Refreshed by every write for a live reply; the outcome switches to the completed retention.
        assertTrue(redis.getExpire(key, TimeUnit.MINUTES) > 55);
        writer.finish(id, Status.COMPLETED, null);
        long done = redis.getExpire(key, TimeUnit.SECONDS);
        assertTrue(done > 590 && done <= 600, "completed TTL " + done);
    }

    @Test
    void aMissingPrefixIsAGapAndAReplyEndedWithoutItsOutcomeResets() throws Exception {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        writer.open(id);
        writer.append(id, "a");
        writer.flush();
        TimeUnit.MILLISECONDS.sleep(30);
        writer.append(id, "b");
        writer.flush();
        redis.opsForStream().delete(StreamBufferWriter.key(id), RecordId.of(0, 1));
        try (var reader = writer.subscribe(id, 0, () -> true)) {
            assertEquals("BUFFER_GAP", reader.read().reset());
        }
        // A reply that ended (a dead writer, a lease reconciled elsewhere) without an outcome entry: drain, then reset.
        try (var reader = writer.subscribe(id, 1, () -> false)) {
            assertEquals(List.of(2L), reader.read().events().stream().map(StreamBufferWriter.Event::sequence).toList());
            var ended = reader.read();
            assertTrue(ended.done());
            assertEquals("BUFFER_GAP", ended.reset());
        }
        try (var reader = writer.subscribe(UUID.randomUUID(), 0, () -> false)) {
            assertEquals("BUFFER_MISSING", reader.read().reset());
        }
        assertEquals(0, writer.readerCount());
    }

    @Test
    void aRunningReplyWithoutEventsHeartbeatsAndChecksLivenessAtEachHeartbeat() throws Exception {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        var checks = new AtomicInteger();
        var running = new AtomicBoolean(true);
        writer.open(id);
        try (var reader = writer.subscribe(id, 0, () -> { checks.incrementAndGet(); return running.get(); })) {
            var heartbeat = reader.read();
            assertTrue(heartbeat.events().isEmpty());
            assertFalse(heartbeat.done());
            assertNull(heartbeat.reset());
            assertEquals(2, checks.get());
            writer.finish(id, Status.CANCELED, null);
            running.set(false);
            var outcome = reader.read();
            assertTrue(outcome.done());
            assertEquals(Status.CANCELED, outcome.events().getLast().status());
        }
    }

    @Test
    void aRevokedReaderEndsWithTheAuthorizationFailure() {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        writer.open(id);
        try (var reader = writer.subscribe(id, 0, () -> { throw ChatException.unavailable(); })) {
            assertThrows(ChatException.class, reader::read);
        }
        assertEquals(0, writer.readerCount());
    }

    @Test
    void pastTheReplyBoundReplayIsTruncatedAsOnyx() throws Exception {
        var writer = new StreamBufferWriter(redis, limits(1024));
        var id = UUID.randomUUID();
        writer.open(id);
        writer.append(id, "x".repeat(20));
        writer.flush();
        TimeUnit.MILLISECONDS.sleep(30);
        writer.append(id, "y".repeat(2000));
        writer.finish(id, Status.COMPLETED, null);
        var events = new ArrayList<StreamBufferWriter.Event>();
        try (var reader = writer.subscribe(id, 0, () -> false)) {
            var batch = reader.read();
            for (; batch.reset() == null; batch = reader.read()) events.addAll(batch.events());
            assertEquals("BUFFER_GAP", batch.reset());
            assertTrue(batch.done());
        }
        assertEquals("x".repeat(20), events.getFirst().text());
        assertTrue(events.stream().noneMatch(event -> event.type().equals("outcome")));
        // Appending stopped at the marker: nothing, not even the outcome, follows it.
        var entries = redis.opsForStream().range(StreamBufferWriter.key(id), Range.unbounded());
        assertEquals(events.size() + 1, entries.size());
        assertEquals("truncated", entries.getLast().getValue().get("type"));
    }

    @Test
    void writesAlreadyInRedisAreAcknowledgedAndTheRestRetried() throws Exception {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        String key = StreamBufferWriter.key(id);
        // As a pipeline that timed out after Redis applied its first entry.
        redis.opsForStream().add(StreamRecords.string(Map.of("type", "text-delta", "data",
                "{\"assistantMessageId\":\"" + id + "\",\"sequence\":1,\"type\":\"text-delta\",\"text\":\"a\",\"hasArtifacts\":false}"))
                .withStreamKey(key).withId(RecordId.of(0, 1)));
        writer.open(id);
        writer.append(id, "a");
        TimeUnit.MILLISECONDS.sleep(30);
        // The tick chunks "a" as sequence 1, which Redis refuses as a duplicate; the writer acknowledges it.
        writer.flush();
        writer.append(id, "b");
        writer.finish(id, Status.COMPLETED, null);
        writer.flush();
        try (var reader = writer.subscribe(id, 0, () -> false)) {
            var events = readAll(reader);
            assertEquals(List.of(1L, 2L, 3L), events.stream().map(StreamBufferWriter.Event::sequence).toList());
            assertEquals("ab", events.stream().map(StreamBufferWriter.Event::text).filter(Objects::nonNull).reduce("", String::concat));
        }
    }

    @Test
    void aRedisOutageQueuesEventsAndKeepsTheSequenceContiguous() throws Exception {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        var docker = TestRedis.container().getDockerClient();
        String container = TestRedis.container().getContainerId();
        writer.open(id);
        writer.append(id, "before ");
        writer.flush();
        docker.pauseContainerCmd(container).exec();
        try {
            TimeUnit.MILLISECONDS.sleep(30);
            writer.append(id, "during");
            // The model writer never waits on Redis; the flush tick fails and keeps the entry.
            writer.flush();
        } finally {
            docker.unpauseContainerCmd(container).exec();
        }
        writer.finish(id, Status.COMPLETED, null);
        // Commands sent during the pause may land late and refuse the retry; the next tick settles the rest.
        writer.flush();
        try (var reader = writer.subscribe(id, 0, () -> false)) {
            var events = readAll(reader);
            assertEquals("before during", events.stream().map(StreamBufferWriter.Event::text).filter(Objects::nonNull).reduce("", String::concat));
            assertEquals(List.of(1L, 2L, 3L), events.stream().map(StreamBufferWriter.Event::sequence).toList());
        }
    }

    @Test
    void unreachableRedisResetsTheReaderToHistoryInsteadOfFailingTheSubscription() throws Exception {
        var factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        factory.afterPropertiesSet();
        factory.start();
        try {
            var writer = new StreamBufferWriter(new StringRedisTemplate(factory), limits);
            try (var reader = writer.subscribe(UUID.randomUUID(), 3, () -> true)) {
                var batch = reader.read();
                assertTrue(batch.done());
                assertEquals("BUFFER_MISSING", batch.reset());
            }
            assertEquals(0, writer.readerCount());
        } finally {
            factory.destroy();
        }
    }

    @Test
    void cursorAheadAndReaderLimitsHaveExplicitOutcomesAndDiscardDeletesTheReply() throws Exception {
        var writer = new StreamBufferWriter(redis, limits);
        var id = UUID.randomUUID();
        writer.open(id);
        writer.append(id, "text");
        writer.flush();
        assertThrows(ChatException.class, () -> writer.subscribe(id, 5, () -> true));
        assertThrows(ChatException.class, () -> writer.subscribe(id, -1, () -> true));
        try (var first = writer.subscribe(id, 0, () -> true); var second = writer.subscribe(id, 0, () -> true)) {
            assertThrows(ChatException.class, () -> writer.subscribe(id, 0, () -> true));
            assertEquals(2, writer.readerCount());
        }
        assertEquals(0, writer.readerCount());
        writer.discard(List.of(id));
        assertFalse(redis.hasKey(StreamBufferWriter.key(id)));
        // Late model output of a deleted reply is not registered anymore.
        assertThrows(IllegalStateException.class, () -> writer.append(id, "late"));
    }

    private static List<StreamBufferWriter.Event> readAll(StreamBufferWriter.Reader reader) throws InterruptedException {
        var events = new ArrayList<StreamBufferWriter.Event>();
        for (var batch = reader.read(); ; batch = reader.read()) {
            events.addAll(batch.events());
            assertNull(batch.reset());
            if (batch.done()) return events;
        }
    }
}
