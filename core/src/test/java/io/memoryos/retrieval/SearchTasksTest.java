package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SearchTasksTest {
    @Test
    void helperTimeoutInterruptsNativeCompletableFutureAndWaitsForItsAccountingToFinish() {
        var interrupted = new AtomicBoolean();
        var accounted = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThrows(SearchTasks.HelperTimeoutException.class, () -> SearchTasks.timed(() -> {
                var nativeFuture = CompletableFuture.supplyAsync(() -> {
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException expected) { interrupted.set(true); Thread.currentThread().interrupt(); }
                    finally { accounted.set(true); }
                    return "late";
                }, command -> SearchTasks.executeNative(executor, command));
                return nativeFuture.get();
            }, Duration.ofMillis(300), () -> {}));
            assertTrue(interrupted.get());
            assertTrue(accounted.get());
        }
    }
}
