package io.memoryos.chat.streaming;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatImageEvent;
import io.memoryos.chat.ChatResearchEvent;
import io.memoryos.chat.ChatToolEvent;
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
    private long total;
    private static final ObjectMapper JSON = new ObjectMapper();

    public StreamBufferWriter(ChatStreamProperties limits) {
        this(limits, System::currentTimeMillis);
    }

    StreamBufferWriter(ChatStreamProperties limits, LongSupplier millis) {
        this.limits = limits;
        this.millis = millis;
    }

    public record Event(UUID assistantMessageId, long sequence, String type, @Nullable String text,
                        @Nullable Status status, @Nullable String failureCode, @Nullable ChatToolEvent tool,
                        @Nullable ChatImageEvent image, boolean hasArtifacts, @Nullable ChatResearchEvent research,
                        @Nullable String parentToolCallId) {
        public Event(UUID assistantMessageId, long sequence, String type, @Nullable String text,
                     @Nullable Status status, @Nullable String failureCode, @Nullable ChatToolEvent tool,
                     @Nullable ChatImageEvent image, boolean hasArtifacts) {
            this(assistantMessageId, sequence, type, text, status, failureCode, tool, image, hasArtifacts, null, null);
        }
        public Event(UUID assistantMessageId, long sequence, String type, @Nullable String text,
                     @Nullable Status status, @Nullable String failureCode, @Nullable ChatToolEvent tool, boolean hasArtifacts) {
            this(assistantMessageId, sequence, type, text, status, failureCode, tool, null, hasArtifacts);
        }
        public Event(UUID assistantMessageId, long sequence, String type, @Nullable String text,
                     @Nullable Status status, @Nullable String failureCode, @Nullable ChatToolEvent tool) {
            this(assistantMessageId, sequence, type, text, status, failureCode, tool, false);
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

    private record Chunk(Event event, int bytes) {
    }

    public synchronized void open(UUID id) {
        if (streams.containsKey(id)) throw new IllegalStateException("Chat stream already exists");
        while (streams.size() >= limits.maxStreams()) {
            var old = streams.values().stream().filter(s -> s.done).findFirst().orElseThrow(ChatException::busy);
            remove(old);
        }
        var stream = new Stream(id);
        stream.writtenAt = millis.getAsLong();
        streams.put(id, stream);
    }

    public synchronized void append(UUID id, String text) {
        appendPending(id, "text-delta", null, text);
    }

    /** Reasoning shares the answer's chunking; a change between text and reasoning flushes the pending chunk first. */
    public synchronized void reasoning(UUID id, String text) {
        appendPending(id, "reasoning", null, text);
    }

    /** Reasoning of a research agent chunks apart from the orchestrator's and from other agents'. */
    public synchronized void reasoning(UUID id, String text, @Nullable String parentToolCallId) {
        appendPending(id, "reasoning", parentToolCallId, text);
    }

    /**
     * Plan and intermediate report deltas chunk like answer text, one pending chunk per agent; the other research
     * events flush the pending chunk and publish at once.
     */
    public synchronized void research(UUID id, ChatResearchEvent event) {
        switch (event.kind()) {
            case PLAN_DELTA -> appendPending(id, "research-plan", null, event.text());
            case REPORT_DELTA -> appendPending(id, "intermediate-report", event.toolCallId(), event.text());
            case BRANCHING -> publishNow(id, "top-level-branching", event);
            case AGENT_START -> publishNow(id, "research-agent-start", event);
            case REPORT_CITATIONS -> publishNow(id, "intermediate-report-citations", event);
        }
    }

    private void publishNow(UUID id, String type, ChatResearchEvent event) {
        var stream = require(id);
        if (stream.done) return;
        flush(stream);
        publish(stream, new Event(id, ++stream.sequence, type, null, null, null, null, null, false, event, null));
    }

    private void appendPending(UUID id, String type, @Nullable String key, String text) {
        var stream = require(id);
        if (stream.done) return;
        if (!stream.pendingType.equals(type) || !java.util.Objects.equals(stream.pendingKey, key)) {
            flush(stream);
            stream.pendingType = type;
            stream.pendingKey = key;
        }
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

    public synchronized void tool(UUID id, ChatToolEvent event) {
        var stream = require(id);
        if (stream.done) return;
        flush(stream);
        publish(stream, new Event(id, ++stream.sequence, "tool", null, null, null, event));
    }

    public synchronized void image(UUID id, ChatImageEvent event) {
        var stream = require(id);
        if (stream.done) return;
        flush(stream);
        publish(stream, new Event(id, ++stream.sequence, "image", null, null, null, null, event, false));
    }

    public synchronized void flush() {
        for (var stream : new ArrayList<>(streams.values())) {
            flush(stream);
            expire(stream);
            if (stream.done && millis.getAsLong() - stream.finishedAt >= limits.doneTtl().toMillis()) remove(stream);
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
        publish(stream, switch (stream.pendingType) {
            case "research-plan" -> new Event(stream.id, ++stream.sequence, stream.pendingType, null, null, null, null, null, false,
                    ChatResearchEvent.plan(text), null);
            case "intermediate-report" -> new Event(stream.id, ++stream.sequence, stream.pendingType, null, null, null, null, null, false,
                    ChatResearchEvent.report(java.util.Objects.requireNonNull(stream.pendingKey), text), null);
            default -> new Event(stream.id, ++stream.sequence, stream.pendingType, text, null, null, null, null, false, null, stream.pendingKey);
        });
    }

    private void publish(Stream stream, Event event) {
        int bytes = 256 + (event.text() == null ? 0 : event.text().getBytes(StandardCharsets.UTF_8).length)
                + (event.tool() == null ? 0 : JSON.writeValueAsBytes(event.tool()).length)
                + (event.image() == null ? 0 : JSON.writeValueAsBytes(event.image()).length)
                + (event.research() == null ? 0 : JSON.writeValueAsBytes(event.research()).length)
                + (event.parentToolCallId() == null ? 0 : event.parentToolCallId().length());
        stream.chunks.addLast(new Chunk(event, bytes));
        stream.bytes += bytes;
        total += bytes;
        stream.writtenAt = millis.getAsLong();
        for (var reader : new ArrayList<>(stream.readers)) {
            reader.liveBytes += bytes;
            if (reader.liveBytes > limits.readerBytes()) reader.reset("BUFFER_GAP");
        }
        trim(stream);
        notifyAll();
    }

    private void trim(Stream stream) {
        while (!stream.chunks.isEmpty() && stream.bytes + stream.pendingBytes > limits.runBytes()) drop(stream, "BUFFER_GAP");
        // Held bytes, not reservations, are bounded: completed replays go first, then this run's oldest events.
        while (total > limits.totalBytes()) {
            var completed = streams.values().stream().filter(other -> other.done && other != stream).findFirst();
            if (completed.isPresent()) remove(completed.get());
            else if (!stream.chunks.isEmpty()) drop(stream, "BUFFER_GAP");
            else break;
        }
    }

    /** As Onyx's refresh-on-write TTL: a live replay expires only after a whole idle TTL; completion has its own TTL. */
    private void expire(Stream stream) {
        if (stream.done || millis.getAsLong() - stream.writtenAt < limits.ttl().toMillis()) return;
        while (!stream.chunks.isEmpty()) drop(stream, "BUFFER_EXPIRED");
    }

    private void drop(Stream stream, String gap) {
        int bytes = stream.chunks.removeFirst().bytes();
        stream.bytes -= bytes;
        total -= bytes;
        stream.gap = gap;
    }

    private void remove(Stream stream) {
        streams.remove(stream.id);
        for (var reader : new ArrayList<>(stream.readers)) reader.reset("BUFFER_MISSING");
        stream.chunks.clear();
        stream.pending.setLength(0);
        total -= stream.bytes;
        stream.bytes = 0;
        stream.pendingBytes = 0;
    }

    private static final class Stream {
        final UUID id;
        final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
        final Set<Reader> readers = new HashSet<>();
        final StringBuilder pending = new StringBuilder();
        String pendingType = "text-delta";
        @Nullable String pendingKey;
        int bytes;
        int pendingBytes;
        long sequence;
        long writtenAt;
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
