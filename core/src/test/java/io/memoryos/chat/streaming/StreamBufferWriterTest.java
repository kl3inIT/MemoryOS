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
    private final ChatStreamProperties limits = new ChatStreamProperties(2048, 4096, Duration.ofMinutes(10),
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
        var writer = new StreamBufferWriter(limits);
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
}
