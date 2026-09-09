package io.memoryos.chat.catalog;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.execution.ChatModelBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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

    @SuppressWarnings("resource") // The cache owns the shared client; this call returns a lease, not client ownership.
    public Lease acquire(UUID id, String revision, Supplier<ChatProviderAdapter.Client> factory) {
        var cleanup = new ArrayList<Entry>();
        Entry entry;
        boolean initialize = false;
        try {
            synchronized (this) {
                if (closed) throw ChatException.providerUnavailable();
                entry = current.get(id);
                if (entry != null && !entry.revision.equals(revision)) {
                    current.remove(id);
                    retire(entry, cleanup);
                    entry = null;
                }
                if (entry == null) {
                    if (live.size() >= capacity) {
                        var iterator = current.entrySet().iterator();
                        while (iterator.hasNext()) {
                            var candidate = iterator.next().getValue();
                            if (candidate.references == 0) {
                                iterator.remove();
                                retire(candidate, cleanup);
                                break;
                            }
                        }
                    }
                    if (live.size() >= capacity) throw ChatException.busy();
                    entry = new Entry(revision);
                    current.put(id, entry);
                    live.add(entry);
                    initialize = true;
                }
                // Reservations include the initializing caller and same-key waiters.
                entry.references++;
            }
        } finally { cleanup.forEach(ChatModelClients::dispose); }
        if (initialize) {
            try { entry.client.complete(Objects.requireNonNull(factory.get())); }
            catch (Throwable failure) {
                synchronized (this) {
                    current.remove(id, entry);
                    entry.retired = true;
                }
                entry.client.completeExceptionally(failure);
            }
        }
        try {
            var client = entry.client.join();
            return new Lease(entry, client.binding());
        } catch (CompletionException failure) {
            release(entry);
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw ChatException.providerUnavailable();
        }
    }

    private void retire(Entry entry, List<Entry> cleanup) {
        entry.retired = true;
        if (entry.references == 0) {
            live.remove(entry);
            cleanup.add(entry);
        }
    }

    private static void dispose(Entry entry) {
        if (entry.client.isCompletedExceptionally()) return;
        try { entry.client.join().close(); }
        catch (RuntimeException failure) { LOG.warn("Chat client cleanup failed ({})", failure.getClass().getSimpleName()); }
    }

    private void release(Entry entry) {
        boolean dispose;
        synchronized (this) {
            entry.references--;
            dispose = entry.references == 0 && entry.retired;
            if (dispose) live.remove(entry);
        }
        if (dispose) dispose(entry);
    }

    @Override public void close() {
        var cleanup = new ArrayList<Entry>();
        synchronized (this) {
            closed = true;
            current.clear();
            for (var entry : List.copyOf(live)) retire(entry, cleanup);
        }
        cleanup.forEach(ChatModelClients::dispose);
    }

    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private final ChatModelBinding binding;
        private final AtomicBoolean released = new AtomicBoolean();
        private Lease(Entry entry, ChatModelBinding binding) { this.entry = entry; this.binding = binding; }
        public ChatModelBinding binding() { return binding; }
        @Override public void close() {
            if (released.compareAndSet(false, true)) release(entry);
        }
    }

    private static final class Entry {
        final String revision;
        final CompletableFuture<ChatProviderAdapter.Client> client = new CompletableFuture<>();
        int references;
        boolean retired;
        Entry(String revision) {
            this.revision = revision;
        }
    }
}
