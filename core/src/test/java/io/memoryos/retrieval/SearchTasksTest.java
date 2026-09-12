package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SearchTasksTest {
    @Test
    void helperTimeoutInterruptsNativeCompletableFutureAndWaitsForItsAccountingToFinish() throws Exception {
        var interrupted = new AtomicBoolean();
        var accounted = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var invocation = executor.submit(() -> SearchTasks.timed(() -> {
                var nativeFuture = CompletableFuture.supplyAsync(() -> {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException expected) { interrupted.set(true); Thread.currentThread().interrupt(); }
                    finally { accounted.set(true); }
                    return "late";
                }, command -> SearchTasks.executeNative(executor, command));
                return nativeFuture.get();
            }, Duration.ofSeconds(3), () -> {}));
            assertTrue(entered.await(2, TimeUnit.SECONDS), "The native operation must start before measuring timeout");
            var failure = assertThrows(ExecutionException.class, () -> invocation.get(6, TimeUnit.SECONDS));
            assertInstanceOf(SearchTasks.HelperTimeoutException.class, failure.getCause());
            assertTrue(interrupted.get());
            assertTrue(accounted.get());
        }
    }

    @Test
    void cleanupIsBoundedButTracksUncooperativeNativeWorkUntilAccountingFinishes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var accounted = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var scope = new SearchTasks.Scope(Duration.ofMillis(50))) {
            try {
                var invocation = executor.submit(() -> {
                    try (var _ = scope.enter()) {
                        return SearchTasks.timed(() -> CompletableFuture.supplyAsync(() -> {
                            entered.countDown();
                            awaitIgnoringInterrupts(release);
                            assertThrows(CancellationException.class, SearchTasks::checkNativeActive);
                            accounted.set(true);
                            return "late";
                        }, command -> SearchTasks.executeNative(executor, command)).get(), Duration.ofSeconds(3), () -> {});
                    }
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var failure = assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS));
                assertInstanceOf(SearchTasks.HelperTimeoutException.class, failure.getCause());
                executor.submit(scope::close).get(1, TimeUnit.SECONDS);
                assertFalse(scope.drained().isDone());
                assertFalse(accounted.get());
                assertThrows(CancellationException.class, scope::enter);
            } finally { release.countDown(); }
            scope.drained().get(3, TimeUnit.SECONDS);
            assertTrue(accounted.get());
        }
    }

    @Test
    void checkedSearchFailuresRetainTheirCause() {
        var cause = new java.io.IOException("fixture detail");
        var failure = assertThrows(SearchUnavailableException.class,
                () -> SearchTasks.run(java.util.List.of(() -> { throw cause; }), () -> {}));
        assertSame(cause, failure.getCause());
        var timedFailure = assertThrows(SearchUnavailableException.class,
                () -> SearchTasks.timed(() -> { throw cause; }, Duration.ofSeconds(3), () -> {}));
        assertSame(cause, timedFailure.getCause());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch release) {
        boolean done = false;
        while (!done) {
            try { release.await(); done = true; }
            catch (InterruptedException ignored) { /* Simulates an uncooperative provider. */ }
        }
    }
}
