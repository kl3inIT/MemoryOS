package io.memoryos.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** One bounded citation namespace for context files, file tools and organization search per turn. */
public final class ChatEvidence {
    public static final ChatToolEvent.Call FILE_CONTEXT = new ChatToolEvent.Call("file-context", "file_context");
    private static final ChatToolEvent.Call FILE_READER = new ChatToolEvent.Call("file-reader", "read_file");
    private final LinkedHashMap<String, ChatSource> sources = new LinkedHashMap<>();
    private int bytes;
    private Consumer<? super ChatToolEvent> events = ignored -> {};
    private Supplier<ChatToolEvent.@Nullable Call> calls = () -> null;

    public synchronized boolean hasEvidence() { return !sources.isEmpty(); }
    public synchronized int nextId() { return sources.size() + 1; }
    public synchronized List<ChatSource> snapshot() { return List.copyOf(sources.values()); }

    public synchronized void publishTo(Consumer<? super ChatToolEvent> consumer) {
        events = consumer;
        sources.values().forEach(source -> events.accept(new ChatToolEvent(FILE_CONTEXT, source)));
    }

    /** File tools attribute their evidence to the tool call in progress. */
    public synchronized void trackCalls(Supplier<ChatToolEvent.@Nullable Call> current) {
        calls = current;
    }

    public synchronized @Nullable ChatSource file(UUID id, String title, @Nullable String mediaType) {
        return register("file:" + id, number -> new ChatSource(number, null, null, title, 0, 0, List.of(), id, null, null,
                mediaType, List.of(), null), currentCall());
    }

    public synchronized @Nullable ChatSource file(UUID id, String title, @Nullable String mediaType, ChatSource.FileLocation location) {
        return register("file:" + id + ":" + location, number -> new ChatSource(number, null, null, title, 0, 0, List.of(), id, location, null,
                mediaType, List.of(), null), currentCall());
    }

    public synchronized @Nullable ChatSource register(String key, IntFunction<ChatSource> factory, ChatToolEvent.Call call) {
        var previous = sources.get(key);
        if (previous != null) return previous;
        if (sources.size() >= 24) return null;
        var source = factory.apply(nextId());
        int size = 512 + source.title().length() * 6
                + (source.mediaType() == null ? 0 : source.mediaType().length() * 6) + source.sourceTypes().size() * 24
                + (source.providerUrl() == null ? 0 : source.providerUrl().length() * 6)
                + (source.web() == null ? 0 : (source.web().url().length() + source.web().excerpt().length()) * 6)
                + source.provenance().stream().mapToInt(p -> 64 + p.provenanceJson().length() * 6).sum();
        if (bytes + size > 131072) return null;
        sources.put(key, source);
        bytes += size;
        events.accept(new ChatToolEvent(call, source));
        return source;
    }

    private ChatToolEvent.Call currentCall() {
        var current = calls.get();
        return current == null ? FILE_READER : current;
    }
}
