package io.memoryos.chat.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.execution.ChatModelBinding;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class ChatModelClientsTest {
    @Test
    void slowInitializationDoesNotBlockCachedModelsAndSameKeyCreatesOnlyOnce() throws Exception {
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var created = new AtomicInteger();
        var closed = new AtomicInteger();
        try (var clients = new ChatModelClients(2); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cachedId = UUID.randomUUID();
            var slowId = UUID.randomUUID();
            try (var cached = clients.acquire(cachedId, "1", () -> client(created, closed))) {
                var first = executor.submit(() -> clients.acquire(slowId, "1", () -> {
                    entered.countDown();
                    await(proceed);
                    return client(created, closed);
                }));
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    var same = executor.submit(() -> clients.acquire(slowId, "1", () -> client(created, closed)));
                    var fast = executor.submit(() -> clients.acquire(cachedId, "1", () -> client(created, closed)));
                    try (var lease = fast.get(5, TimeUnit.SECONDS)) { assertSame(cached.binding(), lease.binding()); }
                    assertThrows(ChatException.class, () -> clients.acquire(UUID.randomUUID(), "1", () -> client(created, closed)));
                    proceed.countDown();
                    try (var one = first.get(5, TimeUnit.SECONDS); var two = same.get(5, TimeUnit.SECONDS)) {
                        assertSame(one.binding(), two.binding());
                    }
                    assertEquals(2, created.get());
                } finally { proceed.countDown(); }
            }
        }
        assertEquals(2, closed.get());
    }

    @Test
    void initializationFailureReleasesCapacityAndCanBeRetried() throws Exception {
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        try (var clients = new ChatModelClients(1); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var id = UUID.randomUUID();
            var first = executor.submit(() -> clients.acquire(id, "1", () -> {
                entered.countDown();
                await(proceed);
                throw new IllegalStateException("fixture failure");
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(ChatException.class, () -> clients.acquire(UUID.randomUUID(), "1", () -> client(new AtomicInteger(), new AtomicInteger())));
            } finally { proceed.countDown(); }
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS)).getCause());
            try (var retried = clients.acquire(id, "1", () -> client(new AtomicInteger(), new AtomicInteger()))) {
                assertNotNull(retried.binding());
            }
        }
    }

    @Test
    @SuppressWarnings("TryFinallyCanBeTryWithResources") // The old lease must close on another thread while this scope stays open.
    void slowCleanupDoesNotBlockAnotherModelLease() throws Exception {
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        try (var clients = new ChatModelClients(3); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var oldId = UUID.randomUUID();
            var cachedId = UUID.randomUUID();
            var old = clients.acquire(oldId, "1", () -> new ChatProviderAdapter.Client(mock(ChatModelBinding.class), () -> {
                entered.countDown();
                await(proceed);
            }));
            try (var changed = clients.acquire(oldId, "2", () -> client(new AtomicInteger(), new AtomicInteger()));
                 var cached = clients.acquire(cachedId, "1", () -> client(new AtomicInteger(), new AtomicInteger()))) {
                assertNotSame(old.binding(), changed.binding());
                var cleanup = executor.submit(old::close);
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    var fast = executor.submit(() -> clients.acquire(cachedId, "1", () -> { throw new AssertionError("cached"); }));
                    try (var lease = fast.get(5, TimeUnit.SECONDS)) { assertSame(cached.binding(), lease.binding()); }
                } finally { proceed.countDown(); }
                cleanup.get(5, TimeUnit.SECONDS);
            } finally { proceed.countDown(); old.close(); }
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    @Test
    void changedRevisionRetainsOldClientUntilItsLastTurnReleasesIt() {
        var closed = new AtomicInteger();
        var created = new AtomicInteger();
        var id = UUID.randomUUID();
        try (var clients = new ChatModelClients(2)) {
            var first = clients.acquire(id, "1", () -> client(created, closed));
            var same = clients.acquire(id, "1", () -> client(created, closed));
            assertSame(first.binding(), same.binding());
            var changed = clients.acquire(id, "2", () -> client(created, closed));
            assertNotSame(first.binding(), changed.binding());
            assertEquals(2, created.get());
            assertEquals(0, closed.get());
            first.close();
            assertEquals(0, closed.get());
            same.close();
            same.close();
            assertEquals(1, closed.get());
            changed.close();
        }
        assertEquals(2, closed.get());
    }

    @Test
    void boundsLiveAndRetiredClientsAndDoesNotCloseActiveCallsDuringShutdown() {
        var closed = new AtomicInteger();
        var created = new AtomicInteger();
        var clients = new ChatModelClients(1);
        var lease = clients.acquire(UUID.randomUUID(), "1", () -> client(created, closed));
        assertThrows(ChatException.class, () -> clients.acquire(UUID.randomUUID(), "1", () -> client(created, closed)));
        clients.close();
        assertEquals(0, closed.get());
        assertThrows(ChatException.class, () -> clients.acquire(UUID.randomUUID(), "1", () -> client(created, closed)));
        lease.close();
        assertEquals(1, closed.get());
    }

    private static ChatProviderAdapter.Client client(AtomicInteger created, AtomicInteger closed) {
        created.incrementAndGet();
        return new ChatProviderAdapter.Client(mock(ChatModelBinding.class), closed::incrementAndGet);
    }
}
