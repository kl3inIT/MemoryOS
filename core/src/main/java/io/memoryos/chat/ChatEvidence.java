package io.memoryos.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/** One bounded citation namespace for context files, file tools and organization search per turn. */
public final class ChatEvidence {
    private final LinkedHashMap<String, ChatSource> sources = new LinkedHashMap<>();
    private int bytes;
    private Consumer<ChatSearchEvent> events = ignored -> {};

    public synchronized boolean hasEvidence() { return !sources.isEmpty(); }
    public synchronized int nextId() { return sources.size() + 1; }
    public synchronized List<ChatSource> snapshot() { return List.copyOf(sources.values()); }

    public synchronized void publishTo(Consumer<ChatSearchEvent> consumer) {
        events = consumer;
        sources.values().forEach(source -> events.accept(new ChatSearchEvent("file-context", ChatSearchEvent.Stage.SOURCE, source)));
    }

    public synchronized @Nullable ChatSource file(UUID id, String title) {
        return register("file:" + id, number -> new ChatSource(number, null, null, title, 0, 0, List.of(), id), "file-reader");
    }

    public synchronized @Nullable ChatSource register(String key, IntFunction<ChatSource> factory, String toolCallId) {
        var previous = sources.get(key);
        if (previous != null) return previous;
        if (sources.size() >= 24) return null;
        var source = factory.apply(nextId());
        int size = 512 + source.title().length() * 6
                + source.provenance().stream().mapToInt(p -> 64 + p.provenanceJson().length() * 6).sum();
        if (bytes + size > 131072) return null;
        sources.put(key, source);
        bytes += size;
        events.accept(new ChatSearchEvent(toolCallId, ChatSearchEvent.Stage.SOURCE, source));
        return source;
    }
}
