package io.memoryos.chat.streaming;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatSearchEvent;
import tools.jackson.databind.ObjectMapper;
import io.memoryos.chat.ChatMessage.Status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * Process-local bounded replay. The monitor protects memory only; no network or database calls occur under it.
 */
public final class StreamBufferWriter {
    private final ChatStreamProperties limits;
    private final LongSupplier millis;
    private final LinkedHashMap<UUID, Stream> streams = new LinkedHashMap<>();
    private int readers;
    private static final ObjectMapper JSON = new ObjectMapper();

    public StreamBufferWriter(ChatStreamProperties limits) {
        this(limits, System::currentTimeMillis);
    }

    StreamBufferWriter(ChatStreamProperties limits, LongSupplier millis) {
        this.limits = limits;
        this.millis = millis;
    }

    public record Event(UUID assistantMessageId, long sequence, String type, @Nullable String text,
                        @Nullable Status status, @Nullable String failureCode, @Nullable ChatSearchEvent search, boolean hasArtifacts) {
        public Event(UUID assistantMessageId, long sequence, String type, @Nullable String text,
                     @Nullable Status status, @Nullable String failureCode, @Nullable ChatSearchEvent search) {
            this(assistantMessageId, sequence, type, text, status, failureCode, search, false);
        }
        public Event(UUID assistantMessageId, long sequence, String type, @Nullable String text,
                     @Nullable Status status, @Nullable String failureCode) {
            this(assistantMessageId, sequence, type, text, status, failureCode, null);
        }
        public String id() {
            return assistantMessageId + ":" + sequence;
        }
    }

    public record Batch(List<Event> events, boolean done, @Nullable String reset) {
    }

    private record Chunk(Event event, int bytes, long created) {
    }

    public synchronized void open(UUID id) {
        if (streams.containsKey(id)) throw new IllegalStateException("Chat stream already exists");
        long capacity = Math.min(limits.maxStreams(), limits.totalBytes() / limits.runBytes());
        while (streams.size() >= capacity) {
            var old = streams.values().stream().filter(s -> s.done).findFirst().orElseThrow(ChatException::busy);
            remove(old);
        }
        streams.put(id, new Stream(id));
    }

    public synchronized void append(UUID id, String text) {
        var stream = require(id);
        if (stream.done) return;
        for (int offset = 0; offset < text.length(); ) {
            int point = text.codePointAt(offset);
            int bytes = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            if (stream.pendingBytes + bytes > limits.chunkBytes()) flush(stream);
            stream.pending.appendCodePoint(point);
            stream.pendingBytes += bytes;
            offset += Character.charCount(point);
            trim(stream);
        }
        if (millis.getAsLong() - stream.flushedAt >= limits.flushInterval().toMillis()) flush(stream);
    }

    public synchronized void finish(UUID id, Status status, @Nullable String failure) {
        finish(id, status, failure, false);
    }

    public synchronized void finish(UUID id, Status status, @Nullable String failure, boolean hasArtifacts) {
        var stream = require(id);
        if (stream.done) return;
        if (status == Status.RUNNING) throw new IllegalArgumentException("Terminal status required");
        flush(stream);
        publish(stream, new Event(id, ++stream.sequence, "outcome", null, status, failure, null, hasArtifacts));
        stream.done = true;
        stream.finishedAt = millis.getAsLong();
        notifyAll();
    }

    public synchronized void search(UUID id, ChatSearchEvent event) {
        var stream = require(id);
        if (stream.done) return;
        flush(stream);
        publish(stream, new Event(id, ++stream.sequence, "search", null, null, null, event));
    }

    public synchronized void flush() {
        for (var stream : new ArrayList<>(streams.values())) {
            flush(stream);
            expire(stream);
            if (stream.done && millis.getAsLong() - stream.finishedAt >= limits.ttl().toMillis()) remove(stream);
        }
    }

    public synchronized void validateSubscription(UUID id, long after) {
        if (after < 0) throw ChatException.invalid("Invalid stream cursor.");
        var stream = streams.get(id);
        if (stream == null) return;
        flush(stream);
        expire(stream);
        if (after > stream.sequence) throw ChatException.invalid("Stream cursor is ahead of this reply.");
        if (after < stream.firstSequence() - 1) return;
        if (readers >= limits.maxReaders() || stream.readers.size() >= limits.readersPerRun())
            throw ChatException.busy();
    }

    public synchronized Reader subscribe(UUID id, long after) {
        validateSubscription(id, after);
        var stream = streams.get(id);
        if (stream == null) return new Reader(null, after, "BUFFER_MISSING");
        if (after < stream.firstSequence() - 1) return new Reader(null, after, stream.gap);
        var reader = new Reader(stream, after, null);
        stream.readers.add(reader);
        readers++;
        return reader;
    }

    public synchronized int readerCount() {
        return readers;
    }

    public synchronized void discard(UUID id) {
        var stream = streams.get(id);
        if (stream != null) remove(stream);
        notifyAll();
    }

    private Stream require(UUID id) {
        var stream = streams.get(id);
        if (stream == null) throw new IllegalStateException("Chat stream was not registered");
        return stream;
    }

    private void flush(Stream stream) {
        if (stream.pending.isEmpty()) return;
        String text = stream.pending.toString();
        stream.pending.setLength(0);
        stream.pendingBytes = 0;
        stream.flushedAt = millis.getAsLong();
        publish(stream, new Event(stream.id, ++stream.sequence, "text-delta", text, null, null));
    }

    private void publish(Stream stream, Event event) {
        int bytes = 256 + (event.text() == null ? 0 : event.text().getBytes(StandardCharsets.UTF_8).length)
                + (event.search() == null ? 0 : JSON.writeValueAsBytes(event.search()).length);
        stream.chunks.addLast(new Chunk(event, bytes, millis.getAsLong()));
        stream.bytes += bytes;
        for (var reader : new ArrayList<>(stream.readers)) {
            reader.liveBytes += bytes;
            if (reader.liveBytes > limits.readerBytes()) reader.reset("BUFFER_GAP");
        }
        trim(stream);
        notifyAll();
    }

    private void trim(Stream stream) {
        while (!stream.chunks.isEmpty() && stream.bytes + stream.pendingBytes > limits.runBytes()) {
            stream.bytes -= stream.chunks.removeFirst().bytes();
            stream.gap = "BUFFER_GAP";
        }
    }

    private void expire(Stream stream) {
        while (!stream.chunks.isEmpty() && millis.getAsLong() - stream.chunks.getFirst().created() >= limits.ttl().toMillis()) {
            stream.bytes -= stream.chunks.removeFirst().bytes();
            stream.gap = "BUFFER_EXPIRED";
        }
    }

    private void remove(Stream stream) {
        streams.remove(stream.id);
        for (var reader : new ArrayList<>(stream.readers)) reader.reset("BUFFER_MISSING");
        stream.chunks.clear();
        stream.pending.setLength(0);
        stream.bytes = 0;
        stream.pendingBytes = 0;
    }

    private static final class Stream {
        final UUID id;
        final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
        final Set<Reader> readers = new HashSet<>();
        final StringBuilder pending = new StringBuilder();
        int bytes;
        int pendingBytes;
        long sequence;
        long flushedAt;
        long finishedAt;
        boolean done;
        String gap = "BUFFER_GAP";

        Stream(UUID id) {
            this.id = id;
        }

        long firstSequence() {
            return chunks.isEmpty() ? sequence + 1 : chunks.getFirst().event().sequence();
        }
    }

    public final class Reader implements AutoCloseable {
        private final @Nullable Stream stream;
        private final long highWater;
        private long after;
        private int liveBytes;
        private boolean closed;
        private @Nullable String reset;

        private Reader(@Nullable Stream stream, long after, @Nullable String reset) {
            this.stream = stream;
            this.after = after;
            this.highWater = stream == null ? after : stream.sequence;
            this.reset = reset;
        }

        public Batch read() throws InterruptedException {
            synchronized (StreamBufferWriter.this) {
                long heartbeatAt = System.nanoTime() + limits.heartbeat().toNanos();
                while (stream != null && !closed && reset == null && after == stream.sequence && !stream.done) {
                    long remaining = heartbeatAt - System.nanoTime();
                    if (remaining <= 0) break;
                    TimeUnit.NANOSECONDS.timedWait(StreamBufferWriter.this, remaining);
                }
                if (reset != null) return new Batch(List.of(), true, reset);
                if (closed || stream == null) return new Batch(List.of(), true, null);
                expire(stream);
                if (after < stream.firstSequence() - 1) {
                    reset(stream.gap);
                    return new Batch(List.of(), true, reset);
                }
                var result = new ArrayList<Event>();
                int bytes = 0;
                for (var chunk : stream.chunks) {
                    if (chunk.event().sequence() <= after) continue;
                    if (bytes + chunk.bytes() > Math.min(limits.readBytes(), limits.readerBytes())) break;
                    result.add(chunk.event());
                    after = chunk.event().sequence();
                    bytes += chunk.bytes();
                    if (after > highWater) liveBytes -= chunk.bytes();
                }
                boolean done = stream.done && after == stream.sequence;
                if (done) close();
                return new Batch(List.copyOf(result), done, null);
            }
        }

        private void reset(String reason) {
            reset = reason;
            close();
        }

        @Override
        public void close() {
            synchronized (StreamBufferWriter.this) {
                if (closed) return;
                closed = true;
                if (stream != null && stream.readers.remove(this)) readers--;
                StreamBufferWriter.this.notifyAll();
            }
        }
    }
}
