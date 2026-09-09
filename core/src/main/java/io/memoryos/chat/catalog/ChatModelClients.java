package io.memoryos.chat.catalog;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.execution.ChatModelBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded ownership of native clients. Leases are per turn, never per SSE subscriber. */
public final class ChatModelClients implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ChatModelClients.class);
    private final int capacity;
    private final LinkedHashMap<UUID, Entry> current = new LinkedHashMap<>(16, .75f, true);
    private final List<Entry> live = new ArrayList<>();
    private boolean closed;

    public ChatModelClients(int capacity) {
        if (capacity < 1 || capacity > 1024) throw new IllegalArgumentException("Invalid model client capacity");
        this.capacity = capacity;
    }

    public synchronized Lease acquire(UUID id, String revision, Supplier<ChatProviderAdapter.Client> factory) {
        if (closed) throw ChatException.providerUnavailable();
        var entry = current.get(id);
        if (entry != null && !entry.revision.equals(revision)) {
            current.remove(id);
            retire(entry);
            entry = null;
        }
        if (entry == null) {
            if (live.size() >= capacity) {
                var iterator = current.entrySet().iterator();
                while (iterator.hasNext()) {
                    var candidate = iterator.next().getValue();
                    if (candidate.references == 0) {
                        iterator.remove();
                        retire(candidate);
                        break;
                    }
                }
            }
            if (live.size() >= capacity) throw ChatException.busy();
            entry = new Entry(revision, factory.get());
            current.put(id, entry);
            live.add(entry);
        }
        entry.references++;
        return new Lease(entry);
    }

    private void retire(Entry entry) {
        entry.retired = true;
        if (entry.references == 0) dispose(entry);
    }

    private void dispose(Entry entry) {
        live.remove(entry);
        try { entry.client.close(); }
        catch (RuntimeException failure) { LOG.warn("Chat client cleanup failed ({})", failure.getClass().getSimpleName()); }
    }

    @Override public synchronized void close() {
        closed = true;
        current.clear();
        for (var entry : List.copyOf(live)) retire(entry);
    }

    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private final AtomicBoolean released = new AtomicBoolean();
        private Lease(Entry entry) { this.entry = entry; }
        public ChatModelBinding binding() { return entry.client.binding(); }
        @Override public void close() {
            synchronized (ChatModelClients.this) {
                if (!released.compareAndSet(false, true)) return;
                entry.references--;
                if (entry.references == 0 && entry.retired) dispose(entry);
            }
        }
    }

    private static final class Entry {
        final String revision;
        final ChatProviderAdapter.Client client;
        int references;
        boolean retired;
        Entry(String revision, ChatProviderAdapter.Client client) {
            this.revision = revision;
            this.client = client;
        }
    }
}
