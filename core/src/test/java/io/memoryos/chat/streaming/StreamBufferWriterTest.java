package io.memoryos.chat.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StreamBufferWriterTest {
    private final ChatStreamProperties limits = new ChatStreamProperties(2048, 4096, Duration.ofMinutes(10), Duration.ofMinutes(5),
            32, Duration.ofMillis(25), 1024, 2, 4, 1024, 8, Duration.ofMillis(5), Duration.ofMinutes(1));

    @Test
    void replayThenLiveRetainsIdentityAndDoesNotDropTerminal() throws Exception {
        var writer = new StreamBufferWriter(limits);
        var id = UUID.randomUUID();
        writer.open(id);
        writer.append(id, "first😀");
        writer.flush();
        try (var reader = writer.subscribe(id, 0)) {
            writer.append(id, "second");
            writer.finish(id, Status.COMPLETED, null);
            var batch = reader.read();
            assertEquals(3, batch.events().size());
            assertEquals("first😀second", batch.events().stream().map(StreamBufferWriter.Event::text).filter(Objects::nonNull).reduce("", String::concat));
            assertEquals(3, batch.events().getLast().sequence());
            assertEquals(Status.COMPLETED, batch.events().getLast().status());
            assertTrue(batch.done());
        }
        try (var reader = writer.subscribe(id, 1)) {
            assertEquals(2, reader.read().events().getFirst().sequence());
        }
    }

    @Test
    void reasoningChunksSeparatelyFromTextAndKeepsPublicationOrder() throws Exception {
        var writer = new StreamBufferWriter(limits);
        var id = UUID.randomUUID();
        writer.open(id);
        writer.reasoning(id, "Think");
        writer.reasoning(id, "ing");
        writer.append(id, "Answer");
        writer.tool(id, new io.memoryos.chat.ChatToolEvent(new io.memoryos.chat.ChatToolEvent.Call("call_1", "read_file"),
                io.memoryos.chat.ChatToolEvent.Stage.STARTED));
        writer.finish(id, Status.COMPLETED, null);
        var events = new java.util.ArrayList<StreamBufferWriter.Event>();
        try (var reader = writer.subscribe(id, 0)) {
            for (var batch = reader.read(); ; batch = reader.read()) {
                events.addAll(batch.events());
                if (batch.done()) break;
            }
        }
        // The first chunk flushes immediately, as for answer text; a type change flushes the pending chunk.
        assertEquals(java.util.List.of("reasoning", "reasoning", "text-delta", "tool", "outcome"), events.stream().map(StreamBufferWriter.Event::type).toList());
        assertEquals("Thinking", events.stream().filter(event -> event.type().equals("reasoning")).map(StreamBufferWriter.Event::text).reduce("", String::concat));
        assertEquals("Answer", events.get(2).text());
    }

    @Test
    void slowReaderAndEvictionDoNotStopWriterAndAdmissionIsReleased() throws Exception {
        var writer = new StreamBufferWriter(limits);
        var id = UUID.randomUUID();
        writer.open(id);
        try (var reader = writer.subscribe(id, 0)) {
            writer.append(id, "x".repeat(4096));
            writer.flush();
            assertEquals("BUFFER_GAP", reader.read().reset());
            writer.finish(id, Status.CANCELED, null);
        }
        try (var reader = writer.subscribe(id, 0)) {
            assertEquals("BUFFER_GAP", reader.read().reset());
        }
        assertThrows(ChatException.class, () -> { try (var invalid = writer.subscribe(id, Long.MAX_VALUE)) { invalid.read(); } });
        assertEquals(0, writer.readerCount());
    }

    @Test
    void completedBufferCanBeEvictedButActiveCapacityIsBounded() throws Exception {
        var writer = new StreamBufferWriter(new ChatStreamProperties(2048, 4096, Duration.ofMinutes(10), Duration.ofMinutes(5),
                32, Duration.ofMillis(25), 1024, 2, 4, 1024, 2, Duration.ofMillis(5), Duration.ofMinutes(1)));
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        writer.open(first);
        writer.open(second);
        assertThrows(ChatException.class, () -> writer.open(UUID.randomUUID()));
        writer.finish(first, Status.COMPLETED, null);
        writer.open(UUID.randomUUID());
        try (var reader = writer.subscribe(first, 0)) {
            assertEquals("BUFFER_MISSING", reader.read().reset());
        }
    }

    @Test
    void expiryAndReaderLimitHaveExplicitOutcomes() throws Exception {
        var clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        var writer = new StreamBufferWriter(limits, () -> clock.get().toEpochMilli());
        var id = UUID.randomUUID();
        writer.open(id);
        writer.append(id, "prefix");
        writer.flush();
        try (var first = writer.subscribe(id, 0); var second = writer.subscribe(id, 0)) {
            assertThrows(ChatException.class, () -> { try (var excess = writer.subscribe(id, 0)) { excess.read(); } });
            first.close();
            clock.set(clock.get().plus(Duration.ofMinutes(11)));
            assertEquals("BUFFER_EXPIRED", second.read().reset());
        }
        assertEquals(0, writer.readerCount());
    }

    @Test
    void liveReplayTtlIsRefreshedByEachWriteAndCompletionHasItsOwnTtl() throws Exception {
        var clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        var writer = new StreamBufferWriter(limits, () -> clock.get().toEpochMilli());
        var id = UUID.randomUUID();
        writer.open(id);
        writer.append(id, "first");
        writer.flush();
        clock.set(clock.get().plus(Duration.ofMinutes(8)));
        writer.append(id, "second");
        writer.flush();
        clock.set(clock.get().plus(Duration.ofMinutes(8)));
        // Sixteen minutes after the first event, but only eight since the last write: nothing has expired.
        try (var reader = writer.subscribe(id, 0)) {
            assertEquals("first", reader.read().events().getFirst().text());
        }
        writer.finish(id, Status.COMPLETED, null);
        clock.set(clock.get().plus(Duration.ofMinutes(4)));
        writer.flush();
        try (var reader = writer.subscribe(id, 0)) {
            assertEquals(3, reader.read().events().size());
        }
        clock.set(clock.get().plus(Duration.ofMinutes(1)));
        writer.flush();
        try (var reader = writer.subscribe(id, 0)) {
            assertEquals("BUFFER_MISSING", reader.read().reset());
        }
    }

    @Test
    void totalBytesBoundHeldBytesAndEvictCompletedReplaysFirst() throws Exception {
        // One text event holds 1256 bytes: a run fits three (4096), the total fits four (5000).
        var writer = new StreamBufferWriter(new ChatStreamProperties(4096, 5000, Duration.ofMinutes(10), Duration.ofMinutes(5),
                1024, Duration.ofMillis(25), 2048, 2, 4, 2048, 8, Duration.ofMillis(5), Duration.ofMinutes(1)));
        var done = UUID.randomUUID();
        var live = UUID.randomUUID();
        writer.open(done);
        writer.append(done, "d".repeat(1000));
        writer.finish(done, Status.COMPLETED, null);
        writer.open(live);
        // Admission no longer reserves the per-run maximum, so more runs than total/run bytes can be open.
        for (int index = 0; index < 5; index++) writer.open(UUID.randomUUID());
        writer.append(live, "l".repeat(1000));
        writer.flush();
        writer.append(live, "m".repeat(1000));
        writer.flush();
        try (var reader = writer.subscribe(done, 0)) {
            assertEquals("d".repeat(1000), reader.read().events().getFirst().text());
        }
        writer.append(live, "n".repeat(1000));
        writer.flush();
        try (var reader = writer.subscribe(done, 0)) {
            assertEquals("BUFFER_MISSING", reader.read().reset());
        }
        try (var reader = writer.subscribe(live, 0)) {
            assertEquals("l".repeat(1000), reader.read().events().getFirst().text());
        }
    }
}
