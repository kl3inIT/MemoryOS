package io.memoryos.chat.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.execution.ChatModelBinding;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChatModelClientsTest {
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
