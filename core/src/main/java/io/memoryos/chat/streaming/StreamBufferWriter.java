package io.memoryos.chat.streaming;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatImageEvent;
import io.memoryos.chat.ChatResearchEvent;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.chat.ChatMessage.Status;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.DefaultStringRedisConnection;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Reply replay in Redis, as Onyx's {@code stream_buffer.py}: one Redis Stream per reply whose entry IDs are the
 * sequences ({@code 0-n}), a TTL refreshed by every write and a shorter one after the outcome, and a {@code truncated}
 * entry past the per-reply byte bound. Chunking stays in this process under a per-reply monitor; Redis is written by
 * the flush tick outside it, so a slow or unavailable Redis never blocks the model writer. Only this process writes a
 * reply it runs; any process can read it.
 */
public final class StreamBufferWriter {
    private static final Logger LOG = LoggerFactory.getLogger(StreamBufferWriter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PREFIX = "memoryos:chat:stream:";
    private static final String TRUNCATED = "truncated";
    private static final String OUTCOME = "outcome";
    /** A read that finds a reply no longer RUNNING waits this long for its outcome, written after the terminal commit. */
    private static final long FINAL_DRAIN_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final StringRedisTemplate redis;
    private final ChatStreamProperties limits;
    private final LongSupplier millis;
    private final ConcurrentHashMap<UUID, Stream> streams = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> readersPerRun = new java.util.HashMap<>();
    private int readers;

    public StreamBufferWriter(StringRedisTemplate redis, ChatStreamProperties limits) {
        this(redis, limits, System::currentTimeMillis);
    }

    StreamBufferWriter(StringRedisTemplate redis, ChatStreamProperties limits, LongSupplier millis) {
        this.redis = redis;
        this.limits = limits;
        this.millis = millis;
    }

    public record Event(UUID assistantMessageId, long sequence, String type, @Nullable String text,
                        @Nullable Status status, @Nullable String failureCode, @Nullable ChatToolEvent tool,
                        @Nullable ChatImageEvent image, boolean hasArtifacts, @Nullable ChatResearchEvent research,
                        @Nullable String parentToolCallId, io.memoryos.chat.@Nullable ChatCodeEvent code) {
        public Event(UUID assistantMessageId, long sequence, String type, @Nullable String text,
                     @Nullable Status status, @Nullable String failureCode, @Nullable ChatToolEvent tool,
                     @Nullable ChatImageEvent image, boolean hasArtifacts, @Nullable ChatResearchEvent research,
                     @Nullable String parentToolCallId) {
            this(assistantMessageId, sequence, type, text, status, failureCode, tool, image, hasArtifacts, research, parentToolCallId, null);
        }
        public Event(UUID assistantMessageId, long sequence, String type, @Nullable String text,
                     @Nullable Status status, @Nullable String failureCode, @Nullable ChatToolEvent tool,
                     @Nullable ChatImageEvent image, boolean hasArtifacts) {
            this(assistantMessageId, sequence, type, text, status, failureCode, tool, image, hasArtifacts, null, null, null);
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

    /** One entry waiting for Redis; {@code data} is null for the truncation marker. */
    private record Entry(long sequence, String type, @Nullable String data) {
    }

    public void open(UUID id) {
        var stream = new Stream(id);
        if (streams.putIfAbsent(id, stream) != null) throw new IllegalStateException("Chat stream already exists");
    }

    public void append(UUID id, String text) {
        appendPending(id, "text-delta", null, text);
    }

    /** Reasoning shares the answer's chunking; a change between text and reasoning flushes the pending chunk first. */
    public void reasoning(UUID id, String text) {
        appendPending(id, "reasoning", null, text);
    }

    /** Reasoning of a research agent chunks apart from the orchestrator's and from other agents'. */
    public void reasoning(UUID id, String text, @Nullable String parentToolCallId) {
        appendPending(id, "reasoning", parentToolCallId, text);
    }

    /**
     * Plan and intermediate report deltas chunk like answer text, one pending chunk per agent; the other research
     * events flush the pending chunk and publish at once.
     */
    public void research(UUID id, ChatResearchEvent event) {
        switch (event.kind()) {
            case PLAN_DELTA -> appendPending(id, "research-plan", null, event.text());
            case REPORT_DELTA -> appendPending(id, "intermediate-report", event.toolCallId(), event.text());
            case BRANCHING -> publishNow(id, "top-level-branching", event);
            case AGENT_START -> publishNow(id, "research-agent-start", event);
            case REPORT_CITATIONS -> publishNow(id, "intermediate-report-citations", event);
            // History carries the clarification flag; the question itself streams as answer text.
            case CLARIFICATION -> { }
        }
    }

    private void publishNow(UUID id, String type, ChatResearchEvent event) {
        var stream = require(id);
        synchronized (stream) {
            if (stream.done) return;
            chunk(stream);
            publish(stream, new Event(id, stream.sequence + 1, type, null, null, null, null, null, false, event, null));
        }
    }

    private void appendPending(UUID id, String type, @Nullable String key, String text) {
        var stream = require(id);
        synchronized (stream) {
            if (stream.done) return;
            if (!stream.pendingType.equals(type) || !java.util.Objects.equals(stream.pendingKey, key)) {
                chunk(stream);
                stream.pendingType = type;
                stream.pendingKey = key;
            }
            for (int offset = 0; offset < text.length(); ) {
                int point = text.codePointAt(offset);
                int bytes = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
                if (stream.pendingBytes + bytes > limits.chunkBytes()) chunk(stream);
                stream.pending.appendCodePoint(point);
                stream.pendingBytes += bytes;
                offset += Character.charCount(point);
            }
            if (millis.getAsLong() - stream.flushedAt >= limits.flushInterval().toMillis()) chunk(stream);
        }
    }

    public void finish(UUID id, Status status, @Nullable String failure) {
        finish(id, status, failure, false);
    }

    /** Called after the terminal transaction commits; the outcome entry is the done marker readers trust. */
    public void finish(UUID id, Status status, @Nullable String failure, boolean hasArtifacts) {
        if (status == Status.RUNNING) throw new IllegalArgumentException("Terminal status required");
        var stream = require(id);
        synchronized (stream) {
            if (stream.done) return;
            chunk(stream);
            publish(stream, new Event(id, stream.sequence + 1, OUTCOME, null, status, failure, null, hasArtifacts));
            stream.done = true;
            stream.finishedAt = millis.getAsLong();
        }
        write(stream);
    }

    public void tool(UUID id, ChatToolEvent event) {
        var stream = require(id);
        synchronized (stream) {
            if (stream.done) return;
            chunk(stream);
            publish(stream, new Event(id, stream.sequence + 1, "tool", null, null, null, event));
        }
    }

    public void image(UUID id, ChatImageEvent event) {
        var stream = require(id);
        synchronized (stream) {
            if (stream.done) return;
            chunk(stream);
            publish(stream, new Event(id, stream.sequence + 1, "image", null, null, null, null, event, false));
        }
    }

    public void code(UUID id, io.memoryos.chat.ChatCodeEvent event) {
        var stream = require(id);
        synchronized (stream) {
            if (stream.done) return;
            chunk(stream);
            publish(stream, new Event(id, stream.sequence + 1, "code", null, null, null, null, null, false, null, null, event));
        }
    }

    /** The flush tick: publishes due pending chunks and writes queued entries; a reply leaves this process once written. */
    public void flush() {
        for (var stream : streams.values()) {
            synchronized (stream) {
                if (!stream.done) chunk(stream);
            }
            write(stream);
            synchronized (stream) {
                // Late events after the outcome are ignored for its completed retention; unwritten entries are given up then.
                if (stream.done && millis.getAsLong() - stream.finishedAt >= limits.doneTtl().toMillis()) streams.remove(stream.id, stream);
            }
        }
    }

    public void validateSubscription(UUID id, long after) {
        if (after < 0) throw ChatException.invalid("Invalid stream cursor.");
        long last;
        // Unreadable replay is reported by the reader as a reset, so the browser falls back to history.
        try { last = lastSequence(id); }
        catch (org.springframework.dao.DataAccessException unavailable) { last = after; }
        if (after > last) throw ChatException.invalid("Stream cursor is ahead of this reply.");
        synchronized (readersPerRun) {
            if (readers >= limits.maxReaders() || readersPerRun.getOrDefault(id, 0) >= limits.readersPerRun())
                throw ChatException.busy();
        }
    }

    /**
     * {@code running} is asked when a read finds nothing new: first at once, then at each heartbeat. It re-authorizes
     * the reader and reports whether the reply is still RUNNING; once it is not, the reader drains what remains and ends,
     * as Onyx ends a resume when the processing fence lapses.
     */
    public Reader subscribe(UUID id, long after, BooleanSupplier running) {
        validateSubscription(id, after);
        synchronized (readersPerRun) {
            readers++;
            readersPerRun.merge(id, 1, Integer::sum);
        }
        return new Reader(id, after, running);
    }

    public int readerCount() {
        synchronized (readersPerRun) {
            return readers;
        }
    }

    /** Session deletion; a Redis failure leaves the keys to their TTL. */
    public void discard(java.util.Collection<UUID> ids) {
        for (var id : ids) {
            var stream = streams.remove(id);
            if (stream != null) synchronized (stream) { stream.done = true; stream.queue.clear(); }
        }
        if (ids.isEmpty()) return;
        try { redis.delete(ids.stream().map(StreamBufferWriter::key).toList()); }
        catch (RuntimeException failure) { LOG.warn("Chat stream deletion unavailable ({})", failure.getClass().getSimpleName()); }
    }

    static String key(UUID id) {
        return PREFIX + id;
    }

    private Stream require(UUID id) {
        var stream = streams.get(id);
        if (stream == null) throw new IllegalStateException("Chat stream was not registered");
        return stream;
    }

    private long lastSequence(UUID id) {
        var stream = streams.get(id);
        if (stream != null) synchronized (stream) { return stream.sequence; }
        var last = redis.opsForStream().reverseRange(key(id), Range.unbounded(), Limit.limit().count(1));
        return last == null || last.isEmpty() ? 0 : last.getFirst().getId().getSequence();
    }

    private void chunk(Stream stream) {
        if (stream.pending.isEmpty()) return;
        String text = stream.pending.toString();
        stream.pending.setLength(0);
        stream.pendingBytes = 0;
        stream.flushedAt = millis.getAsLong();
        long sequence = stream.sequence + 1;
        publish(stream, switch (stream.pendingType) {
            case "research-plan" -> new Event(stream.id, sequence, stream.pendingType, null, null, null, null, null, false,
                    ChatResearchEvent.plan(text), null);
            case "intermediate-report" -> new Event(stream.id, sequence, stream.pendingType, null, null, null, null, null, false,
                    ChatResearchEvent.report(java.util.Objects.requireNonNull(stream.pendingKey), text), null);
            default -> new Event(stream.id, sequence, stream.pendingType, text, null, null, null, null, false, null, stream.pendingKey);
        });
    }

    /** As Onyx: past the per-reply bound one marker is written and the reply stops appending; readers fall back to history. */
    private void publish(Stream stream, Event event) {
        if (stream.truncated) return;
        String data = JSON.writeValueAsString(event);
        int bytes = data.getBytes(StandardCharsets.UTF_8).length;
        stream.sequence = event.sequence();
        if (stream.bytes + bytes > limits.runBytes()) {
            stream.truncated = true;
            stream.queue.addLast(new Entry(event.sequence(), TRUNCATED, null));
            LOG.warn("Chat stream for reply {} exceeded {} bytes; replay is truncated", stream.id, limits.runBytes());
            return;
        }
        stream.bytes += bytes;
        stream.queue.addLast(new Entry(event.sequence(), event.type(), data));
    }

    private void write(Stream stream) {
        if (!stream.writing.tryLock()) return;
        try {
            List<Entry> batch;
            synchronized (stream) {
                batch = List.copyOf(stream.queue);
            }
            if (batch.isEmpty()) return;
            String key = key(stream.id);
            try {
                // Plain commands on the shared connection: a pipeline would take a dedicated connection on every tick.
                redis.execute((RedisCallback<Object>) connection -> {
                    append(connection, key, batch);
                    return null;
                });
                acknowledge(stream, batch.getLast().sequence());
                if (stream.failing) LOG.info("Chat stream writes to Redis resumed for reply {}", stream.id);
                stream.failing = false;
            } catch (RuntimeException failure) {
                // A timed-out pipeline may have been applied: entries Redis already holds are dropped, the rest retried.
                if (!stream.failing) LOG.warn("Chat stream write to Redis failed for reply {} ({}); retrying",
                        stream.id, failure.getClass().getSimpleName());
                stream.failing = true;
                try {
                    var last = redis.opsForStream().reverseRange(key, Range.unbounded(), Limit.limit().count(1));
                    if (last != null && !last.isEmpty()) acknowledge(stream, last.getFirst().getId().getSequence());
                } catch (RuntimeException ignored) {
                    // Redis is unavailable; the queue stays bounded by the per-reply bytes and is retried next tick.
                }
            }
        } finally {
            stream.writing.unlock();
        }
    }

    private void append(RedisConnection connection, String key, List<Entry> batch) {
        var strings = new DefaultStringRedisConnection(connection);
        boolean terminal = false;
        for (var entry : batch) {
            Map<String, String> fields = entry.data() == null ? Map.of("type", entry.type())
                    : Map.of("type", entry.type(), "data", entry.data());
            strings.xAdd(StreamRecords.string(fields).withStreamKey(key).withId(RecordId.of(0, entry.sequence())));
            terminal |= entry.type().equals(OUTCOME) || entry.type().equals(TRUNCATED);
        }
        // Refreshed by every write, as Onyx; the outcome switches the reply to its completed retention.
        strings.pExpire(key, (terminal && batch.getLast().type().equals(OUTCOME) ? limits.doneTtl() : limits.ttl()).toMillis());
    }

    private static void acknowledge(Stream stream, long written) {
        synchronized (stream) {
            while (!stream.queue.isEmpty() && stream.queue.getFirst().sequence() <= written) stream.queue.removeFirst();
        }
    }

    private static final class Stream {
        final UUID id;
        final ArrayDeque<Entry> queue = new ArrayDeque<>();
        final StringBuilder pending = new StringBuilder();
        final ReentrantLock writing = new ReentrantLock();
        String pendingType = "text-delta";
        @Nullable String pendingKey;
        int pendingBytes;
        long bytes;
        long sequence;
        long flushedAt;
        long finishedAt;
        boolean done;
        boolean truncated;
        volatile boolean failing;

        Stream(UUID id) {
            this.id = id;
        }
    }

    public final class Reader implements AutoCloseable {
        private final UUID id;
        private final BooleanSupplier running;
        private long after;
        private boolean checked;
        private long drainUntil;
        private volatile boolean closed;

        private Reader(UUID id, long after, BooleanSupplier running) {
            this.id = id;
            this.after = after;
            this.running = running;
        }

        /**
         * Replays from the cursor, then polls Redis every {@code poll-interval} until events arrive or a heartbeat is
         * due, as Onyx's resume endpoint. Returns done after the outcome, a reset on a gap, truncation or missing buffer.
         */
        public Batch read() throws InterruptedException {
            long heartbeatAt = System.nanoTime() + limits.heartbeat().toNanos();
            while (!closed) {
                Batch batch;
                try { batch = next(); }
                catch (org.springframework.dao.DataAccessException unavailable) {
                    // As Onyx's resume endpoint without a buffer: the browser reads history and polls while RUNNING.
                    LOG.warn("Chat stream replay unavailable for reply {} ({})", id, unavailable.getClass().getSimpleName());
                    return end("BUFFER_MISSING");
                }
                if (batch != null) return batch;
                long now = System.nanoTime();
                if (drainUntil != 0) {
                    if (now >= drainUntil) return end(exists() ? "BUFFER_GAP" : "BUFFER_MISSING");
                } else if (!checked || now >= heartbeatAt) {
                    boolean first = !checked;
                    checked = true;
                    if (!running.getAsBoolean()) drainUntil = now + FINAL_DRAIN_NANOS;
                    else if (!first) return new Batch(List.of(), false, null);
                }
                TimeUnit.NANOSECONDS.sleep(limits.pollInterval().toNanos());
            }
            return new Batch(List.of(), true, null);
        }

        private @Nullable Batch next() {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(key(id),
                    Range.rightUnbounded(Range.Bound.inclusive("0-" + (after + 1))), Limit.limit().count(64));
            if (records == null || records.isEmpty()) return null;
            var events = new ArrayList<Event>();
            int bytes = 0;
            for (var record : records) {
                long sequence = record.getId().getSequence();
                if (sequence != after + 1) return events.isEmpty() ? end("BUFFER_GAP") : new Batch(List.copyOf(events), false, null);
                String type = String.valueOf(record.getValue().get("type"));
                if (type.equals(TRUNCATED)) return events.isEmpty() ? end("BUFFER_GAP") : new Batch(List.copyOf(events), false, null);
                String data = String.valueOf(record.getValue().get("data"));
                var event = JSON.readValue(data, Event.class);
                events.add(event);
                after = sequence;
                if (type.equals(OUTCOME)) {
                    close();
                    return new Batch(List.copyOf(events), true, null);
                }
                bytes += data.length();
                if (bytes >= limits.readBytes()) break;
            }
            return new Batch(List.copyOf(events), false, null);
        }

        private boolean exists() {
            try { return Boolean.TRUE.equals(redis.hasKey(key(id))); }
            catch (org.springframework.dao.DataAccessException unavailable) { return false; }
        }

        private Batch end(String reason) {
            close();
            return new Batch(List.of(), true, reason);
        }

        @Override
        public void close() {
            synchronized (readersPerRun) {
                if (closed) return;
                closed = true;
                readers--;
                readersPerRun.computeIfPresent(id, (key, count) -> count <= 1 ? null : count - 1);
            }
        }
    }
}
